package tech.cryptonomic.conseil.api.routes.platform.discovery

import cats.effect.{ContextShift, IO}
import com.rklaehn.radixtree.RadixTree
import slick.dbio.{DBIO, DBIOAction}
import slick.jdbc.meta.{MColumn, MIndexInfo, MPrimaryKey, MTable}
import tech.cryptonomic.conseil.api.metadata.AttributeValuesCacheConfiguration
import tech.cryptonomic.conseil.api.routes.platform.Sanitizer
import tech.cryptonomic.conseil.api.sql.DefaultDatabaseOperations._
import tech.cryptonomic.conseil.common.cache.MetadataCaching
import tech.cryptonomic.conseil.common.generic.chain.DataTypes.{
  AttributesValidationError,
  HighCardinalityAttribute,
  InvalidAttributeDataType,
  InvalidAttributeFilterLength
}
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes.DataType.DataType
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes._
import tech.cryptonomic.conseil.common.generic.chain.{DBIORunner, PlatformDiscoveryOperations}
import tech.cryptonomic.conseil.common.metadata._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/** Companion object providing apply method implementation */
object GenericPlatformDiscoveryOperations {

  def apply(
      dbRunner: DBIORunner,
      caching: MetadataCaching[IO],
      cacheOverrides: AttributeValuesCacheConfiguration,
      cacheTTL: FiniteDuration,
      highCardinalityLimit: Int
  )(implicit executionContext: ExecutionContext, contextShift: ContextShift[IO]): GenericPlatformDiscoveryOperations =
    new GenericPlatformDiscoveryOperations(dbRunner, caching, cacheOverrides, cacheTTL, highCardinalityLimit)
}

/** Class providing the implementation of the metadata calls with caching */
class GenericPlatformDiscoveryOperations(
    dbRunner: DBIORunner,
    caching: MetadataCaching[IO],
    cacheOverrides: AttributeValuesCacheConfiguration,
    cacheTTL: FiniteDuration,
    highCardinalityLimit: Int
)(implicit executionContext: ExecutionContext, contextShift: ContextShift[IO])
    extends PlatformDiscoveryOperations {

  import MetadataCaching._
  import cats.effect._
  import cats.implicits._

  /** Method for initializing values of the cache */
  def init(config: List[(Platform, Network)]): Future[Unit] = {
    val platforms = config.map(_._1)

    val entities = IO.fromFuture(IO(dbRunner.runQuery(preCacheEntities(config))))
    val attributes = IO.fromFuture(IO(dbRunner.runQuery(preCacheAttributes(platforms))))
    val attributeValues = IO.fromFuture(IO(dbRunner.runQuery(preCacheAttributeValues(platforms))))

    (
      entities flatMap caching.fillEntitiesCache,
      attributes flatMap caching.fillAttributesCache,
      attributeValues flatMap caching.fillAttributeValuesCache
    ).tupled.void.unsafeToFuture
  }

  /** Pre-caching attributes from slick without cardinality for multiple platforms
    *
    * @param  platforms list of platforms for which we want to fetch attributes
    * @return database action with attributes to be cached
    **/
  private def preCacheAttributes(platforms: List[Platform]): DBIO[AttributesCache] =
    DBIO.sequence(platforms.map(preCacheAttributes)).map(_.reduce(_ ++ _))

  /** Pre-caching attributes from slick without cardinality
    *
    * @param  platform platform for which we want to fetch attributes
    * @return database action with attributes to be cached
    * */
  private def preCacheAttributes(platform: Platform): DBIO[AttributesCache] = {
    val result = for {
      tables <- MTable.getTables(Some(""), Some(platform.name), Some(""), Some(Seq("TABLE")))
      columns <- getColumns(tables)
      indexes <- getIndexes(tables)
      primaryKeys <- getPrimaryKeys(tables)
    } yield
      for {
        cols <- columns
        head <- cols.headOption.toVector
        key = AttributesCacheKey(platform.name, head.table.name)
        entry = CacheEntry(0L, cols.map(c => makeAttributes(c, primaryKeys, indexes)).toList)
      } yield key -> entry

    result.map(_.toMap)
  }

  /** MTable query for getting columns from the DB */
  private def getColumns(tables: Vector[MTable]): DBIO[Vector[Vector[MColumn]]] =
    DBIOAction.sequence {
      tables.map { table =>
        table.getColumns
      }
    }

  /** MTable query for getting indexes from the DB */
  private def getIndexes(tables: Vector[MTable]): DBIO[Vector[Vector[MIndexInfo]]] =
    DBIOAction.sequence {
      tables.map { table =>
        table.getIndexInfo()
      }
    }

  /** MTable query for getting primary keys from the DB */
  private def getPrimaryKeys(tables: Vector[MTable]): DBIO[Vector[Vector[MPrimaryKey]]] =
    DBIOAction.sequence {
      tables.map { table =>
        table.getPrimaryKeys
      }
    }

  /** Makes attributes out of parameters */
  private def makeAttributes(
      col: MColumn,
      primaryKeys: Vector[Vector[MPrimaryKey]],
      indexes: Vector[Vector[MIndexInfo]]
  ): Attribute =
    Attribute(
      name = col.name,
      displayName = makeDisplayName(col.name),
      dataType = mapDataType(col.typeName),
      cardinality = if (canQueryType(mapDataType(col.typeName))) Some(0) else None,
      keyType = if (isIndex(col, indexes) || isKey(col, primaryKeys)) KeyType.UniqueKey else KeyType.NonKey,
      entity = col.table.name
    )

  /** Checks if given MColumn has primary key */
  private def isKey(column: MColumn, keys: Vector[Vector[MPrimaryKey]]): Boolean =
    keys
      .filter(_.forall(_.table == column.table))
      .flatten
      .exists(_.column == column.name)

  /** Checks if given MColumn has index */
  private def isIndex(column: MColumn, index: Vector[Vector[MIndexInfo]]): Boolean =
    index
      .filter(_.forall(_.table == column.table))
      .flatten
      .exists(_.column.contains(column.name))

  /** Pre-caching attribute values from slick for multiple platforms
    *
    * @param  platforms the list of platforms for which we want to fetch attribute values
    * @return database action with attribute values to be cached
    * */
  private def preCacheAttributeValues(platforms: List[Platform]): DBIO[AttributeValuesCache] =
    DBIO.sequence(platforms.map(preCacheAttributeValues)).map(_.reduce(_ ++ _))

  /** Pre-caching attribute values from slick for specific platform
    *
    * @param  platform platform for which we want to fetch attribute values
    * @return database action with attribute values to be cached
    * */
  private def preCacheAttributeValues(platform: Platform): DBIO[AttributeValuesCache] =
    DBIO.sequence {
      cacheOverrides.getAttributesToCache.collect {
        case (schema, table, column) if schema == platform.name =>
          selectDistinct(platform.name, table, column).map { values =>
            val radixTree = RadixTree(values.map(x => x.toLowerCase -> x): _*)
            AttributeValuesCacheKey(platform.name, table, column) -> CacheEntry(now, radixTree)
          }
      }
    }.map(_.toMap)

  /**
    * Extracts entities in the DB for the given network
    *
    * @return list of entities as a Future
    */
  override def getEntities(networkPath: NetworkPath): Future[List[Entity]] = {
    val key = EntitiesCacheKey(networkPath.up.platform, networkPath.network)
    val result = for {
      entities <- caching.getEntities(key)
      res <- entities.traverse {
        case CacheEntry(last, ent) =>
          if (!cacheExpired(last)) {
            IO.pure(ent)
          } else {
            (for {
              _ <- caching.putEntities(key, ent)
              _ <- contextShift.shift
              updatedEntities <- IO.fromFuture(
                IO(dbRunner.runQuery(preCacheEntities(networkPath.up.platform, networkPath.network)))
              )
              _ <- caching.putAllEntities(updatedEntities)
            } yield ()).unsafeRunAsyncAndForget()
            IO.pure(ent)
          }
      }
    } yield res.toList.flatten
    result.unsafeToFuture()
  }

  /** Pre-caching entities values from slick for multiple platform-network pairs
    *
    * @param xs list of platform-network pairs for which we want to fetch entities
    * @return database action with entities to be cached
    * */
  private def preCacheEntities(xs: List[(Platform, Network)]): DBIO[EntitiesCache] =
    DBIO
      .sequence(xs.map { case (platform, network) => preCacheEntities(platform.name, network.name) })
      .map(_.reduce(_ ++ _))

  /** Pre-caching entities values from slick for specific platform-network pair
    *
    * @param platform platform for which we want to fetch entities
    * @param network  network for which we want to fetch entities within specific platform
    * @return database action with entities to be cached
    * */
  private def preCacheEntities(platform: String, network: String): DBIO[EntitiesCache] = {
    val result = for {
      tables <- MTable.getTables(Some(""), Some(platform), Some(""), Some(Seq("TABLE")))
      counts <- getTablesCount(platform, tables)
    } yield
      now -> (tables.map(_.name.name) zip counts).map {
            case (name, count) =>
              Entity(name, makeDisplayName(name), count)
          }.toList
    result.map(value => Map(EntitiesCacheKey(platform, network) -> CacheEntry(value._1, value._2)))
  }

  /** Makes displayName out of name */
  private def makeDisplayName(name: String): String =
    name.capitalize.replace("_", " ")

  /** Query for counting rows in the table */
  private def getTablesCount(platform: String, tables: Vector[MTable]): DBIO[Vector[Int]] =
    DBIOAction.sequence {
      tables.map { table =>
        countRows(platform, table.name.name)
      }
    }

  /** Makes list of possible string values of the attributes
    *
    * @param  attributePath         path of the attribute to extract caching config
    * @param  withFilter            optional parameter which can filter attributes
    * @param  attributesCacheConfig optional parameter available when attribute needs to be cached
    * @return Either list of attributes or list of errors
    * */
  override def listAttributeValues(
      attributePath: AttributePath,
      withFilter: Option[String] = None,
      attributesCacheConfig: Option[AttributeCacheConfiguration] = None
  ): Future[Either[List[AttributesValidationError], List[String]]] =
    getTableAttributesWithoutUpdatingCache(attributePath.up) map (_.flatMap(_.find(_.name == attributePath.attribute))) flatMap {
        attrOpt =>
          val res = (attributesCacheConfig, withFilter) match {
            case (Some(AttributeCacheConfiguration(cached, minMatchLength, maxResultLength)), Some(attributeFilter))
                if cached =>
              Either.cond(
                test = attributeFilter.length >= minMatchLength,
                right = getAttributeValuesFromCache(
                  attributePath.up.up.up.platform,
                  attributePath.up.entity,
                  attributePath.attribute,
                  attributeFilter,
                  maxResultLength
                ),
                left = Future.successful(List(InvalidAttributeFilterLength(attributePath.attribute, minMatchLength)))
              )
            case (Some(AttributeCacheConfiguration(cached, _, maxResultLength)), None) if cached =>
              Right(
                getAttributeValuesFromCache(
                  attributePath.up.up.up.platform,
                  attributePath.up.entity,
                  attributePath.attribute,
                  "",
                  maxResultLength
                )
              )
            case _ =>
              val invalidDataTypeValidationResult =
                if (!attrOpt.exists(attr => canQueryType(attr.dataType)))
                  Some(InvalidAttributeDataType(attributePath.attribute))
                else None
              val highCardinalityValidationResult =
                if (!isLowCardinality(attrOpt.flatMap(_.cardinality)))
                  Some(HighCardinalityAttribute(attributePath.attribute))
                else None
              val validationErrors = List(invalidDataTypeValidationResult, highCardinalityValidationResult).flatten
              Either.cond(
                test = validationErrors.isEmpty,
                right = attrOpt
                  .map(
                    attr =>
                      makeAttributesQuery(
                        attributePath.up.up.up.platform,
                        attributePath.up.entity,
                        attr.name,
                        withFilter
                      )
                  )
                  .toList
                  .sequence
                  .map(_.flatten),
                left = Future.successful(validationErrors)
              )
          }
          res.bisequence
      }

  /** Gets attribute values from cache and updates them if necessary */
  private def getAttributeValuesFromCache(
      platform: String,
      tableName: String,
      columnName: String,
      attributeFilter: String,
      maxResultLength: Int
  ): Future[List[String]] =
    caching
      .getAttributeValues(AttributeValuesCacheKey(platform, tableName, columnName))
      .flatMap {
        case Some(CacheEntry(last, radixTree)) if !cacheExpired(last) =>
          IO.pure(radixTree.filterPrefix(attributeFilter.toLowerCase).values.take(maxResultLength).toList)
        case Some(CacheEntry(_, oldRadixTree)) =>
          (for {
            _ <- caching.putAttributeValues(AttributeValuesCacheKey(platform, tableName, columnName), oldRadixTree)
            _ <- contextShift.shift
            attributeValues <- IO.fromFuture(IO(makeAttributesQuery(platform, tableName, columnName, None)))
            radixTree = RadixTree(attributeValues.map(x => x.toLowerCase -> x): _*)
            _ <- caching.putAttributeValues(AttributeValuesCacheKey(platform, tableName, columnName), radixTree)
          } yield ()).unsafeRunAsyncAndForget()
          IO.pure(oldRadixTree.filterPrefix(attributeFilter).values.take(maxResultLength).toList)
        case None =>
          IO.pure(List.empty)
      }
      .unsafeToFuture()

  /** Makes list of possible string values of the attributes
    *
    * @param  platform name of the schema
    * @param  tableName  name of the table from which we extract attributes
    * @param  column     name of the attribute
    * @param  withFilter optional parameter which can filter attributes
    * @return list of attributes
    * */
  private def makeAttributesQuery(
      platform: String,
      tableName: String,
      column: String,
      withFilter: Option[String]
  ): Future[List[String]] =
    withFilter match {
      case Some(filter) =>
        dbRunner.runQuery(
          selectDistinctLike(platform, tableName, column, Sanitizer.sanitizeForSql(filter))
        )
      case None =>
        dbRunner.runQuery(selectDistinct(platform, tableName, column))
    }

  /**
    * Extracts attributes in the DB for the given platform and table name
    *
    * @param  entityPath path to the table from which we extract attributes
    * @return list of attributes as a Future
    */
  override def getTableAttributes(entityPath: EntityPath): Future[Option[List[Attribute]]] =
    caching.getCachingStatus.map { status =>
      if (status == Finished) {
        val key = AttributesCacheKey(entityPath.up.up.platform, entityPath.entity)
        caching
          .getAttributes(key)
          .flatMap { attributesOpt =>
            attributesOpt.map {
              case CacheEntry(last, attributes) =>
                if (!cacheExpired(last)) {
                  IO.pure(attributes)
                } else {
                  (for {
                    _ <- caching.putAttributes(key, attributes)
                    _ <- contextShift.shift
                    updatedAttributes <- IO.fromFuture(IO(getUpdatedAttributes(entityPath, attributes)))
                    _ <- caching.putAttributes(key, updatedAttributes)
                  } yield ()).unsafeRunAsyncAndForget()
                  IO.pure(attributes)
                }
            }.sequence
          }
          .unsafeToFuture()
      } else {
        // if caching is not finished cardinality should be set to None
        getTableAttributesWithoutUpdatingCache(entityPath).map(_.map(_.map(_.copy(cardinality = None))))
      }
    }.unsafeToFuture().flatten

  /** Checks if cache expired */
  private def cacheExpired(lastUpdated: LastUpdated): Boolean =
    lastUpdated + cacheTTL.toNanos < now

  /**
    * Extracts attributes in the DB for the given table name without updating counts
    *
    * @param  entityPath path of the table from which we extract attributes
    * @return list of attributes as a Future
    */
  override def getTableAttributesWithoutUpdatingCache(entityPath: EntityPath): Future[Option[List[Attribute]]] =
    caching
      .getAttributes(AttributesCacheKey(entityPath.up.up.platform, entityPath.entity))
      .map { attrOpt =>
        attrOpt.map(_.value)
      }
      .unsafeToFuture()

  /** Runs query and attributes with updated counts */
  private def getUpdatedAttributes(entityPath: EntityPath, columns: List[Attribute]): Future[List[Attribute]] =
    dbRunner.runQuery(getUpdatedAttributesQuery(entityPath, columns))

  /** Query for returning partial attributes with updated counts */
  private def getUpdatedAttributesQuery(entityPath: EntityPath, columns: List[Attribute]): DBIO[List[Attribute]] =
    DBIOAction.sequence {
      columns.map { column =>
        val attributePath = AttributePath(column.name, entityPath)
        val cardinalityHint = cacheOverrides.getCardinalityHint(attributePath)
        if (cardinalityHint.exists(_ > highCardinalityLimit)) {
          DBIOAction.successful(column.copy(cardinality = cardinalityHint))
        } else if (canQueryType(column.dataType) && isLowCardinality(column.cardinality)) {
          countDistinct(entityPath.up.up.platform, entityPath.entity, column.name).map { count =>
            column.copy(cardinality = Some(count))
          }
        } else {
          DBIOAction.successful(column)
        }
      }
    }

  /** Checks the data types if cannot be queried by */
  private def canQueryType(dt: DataType): Boolean = {
    // values described in the ticket #183
    val cantQuery = Set(DataType.Date, DataType.DateTime, DataType.Int, DataType.LargeInt, DataType.Decimal)
    !cantQuery(dt)
  }

  /** Checks if cardinality of the column is not too high so it should not be queried */
  private def isLowCardinality(distinctCount: Option[Int]): Boolean = {
    // reasonable value which I thought of for now
    val maxCount = 1000
    distinctCount.getOrElse(maxCount) < maxCount
  }

  /** Starts initialization of attributes count cache */
  def initAttributesCache(config: List[(Platform, Network)]): Future[Unit] = {
    def initSingleCacheEntry(platform: Platform, network: Network): IO[Unit] =
      for {
        entCache <- caching.getEntities(EntitiesCacheKey(platform.name, network.name))
        attrCache <- entCache.toList
          .map(_.value.map(e => AttributesCacheKey(platform.name, e.name)).toSet)
          .map(keys => caching.getAllAttributesByKeys(keys))
          .sequence
          .map(_.reduce(_ ++ _))
        _ <- contextShift.shift
        updatedAttributes <- entCache.foldMapM(
          (entC: CacheEntry[List[Entity]]) =>
            getAllUpdatedAttributes(platform.name, network.name, entC.value, attrCache)
        )
        _ <- updatedAttributes.traverse {
          case (tableName, attr) => caching.putAttributes(AttributesCacheKey(platform.name, tableName), attr)
        }
      } yield ()

    for {
      _ <- caching.updateCachingStatus(InProgress)
      _ <- config.map(c => initSingleCacheEntry(c._1, c._2)).sequence.void
      _ <- caching.updateCachingStatus(Finished)
    } yield ()

  }.unsafeToFuture()

  /** Helper method for updating */
  private def getAllUpdatedAttributes(
      platform: String,
      network: String,
      entities: List[Entity],
      attributes: AttributesCache
  ): IO[List[(String, List[Attribute])]] = {
    val queries = attributes.filterKeys { cacheKey =>
      entities.map(_.name).toSet(cacheKey.key)
    }.mapValues {
      case CacheEntry(_, attrs) => attrs
    }.map {
      case (entityName, attrs) =>
        // dummy entity path because at this level network and platform are not checked
        val entityPath = EntityPath(entityName.key, NetworkPath(network, PlatformPath(platform)))
        getUpdatedAttributesQuery(entityPath, attrs).map(entityName.key -> _)
    }
    val action = DBIO.sequence(queries).map(_.toList)
    IO.fromFuture(IO(dbRunner.runQuery(action)))
  }

}
