package tech.cryptonomic.conseil.common.cache

import cats.effect._
import com.rklaehn.radixtree.RadixTree
import org.scalatest.{Matchers, OneInstancePerTest, OptionValues, WordSpec}
import tech.cryptonomic.conseil.common.cache.MetadataCaching._
import tech.cryptonomic.conseil.common.generic.chain.PlatformDiscoveryTypes.{Attribute, DataType, Entity, KeyType}

import scala.concurrent.ExecutionContext

class MetadataCachingTest extends WordSpec with Matchers with OneInstancePerTest with OptionValues {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

  val sut: MetadataCaching[IO] = MetadataCaching.empty[IO].unsafeRunSync()

  "Metadata caching" should {
      "get initial state of caching status" in {
        sut.getCachingStatus.unsafeRunSync() shouldBe NotStarted
      }

      "update cache status" in {
        sut.updateCachingStatus(InProgress).unsafeRunSync()

        sut.getCachingStatus.unsafeRunSync() shouldBe InProgress
      }

      "init attributes cache" in {
        val emptyAttributesCache: AttributesCache =
          Map(AttributeCacheKey("testPlatform", "testTable") -> CacheEntry(0L, List()))
        sut.fillAttributesCache(emptyAttributesCache).unsafeRunSync()

        sut.getAttributes(AttributeCacheKey("not valid", "testTable")).unsafeRunSync() shouldBe None
        sut.getAttributes(AttributeCacheKey("testPlatform", "not valid")).unsafeRunSync() shouldBe None
        sut.getAttributes(AttributeCacheKey("testPlatform", "")).unsafeRunSync() shouldBe None
        sut.getAttributes(AttributeCacheKey("", "")).unsafeRunSync() shouldBe None
        sut.getAttributes(AttributeCacheKey("testPlatform", "testTable")).unsafeRunSync() shouldBe Some(CacheEntry(0L, List()))
      }

      "init entities cache" in {
        val emptyEntitiesCache: EntitiesCache =
          Map(EntityCacheKey("testPlatform", "testNetwork") -> CacheEntry(0L, List()))
        sut.fillEntitiesCache(emptyEntitiesCache).unsafeRunSync()

        sut.getEntities(EntityCacheKey("testPlatform", "not valid")).unsafeRunSync() shouldBe None
        sut.getEntities(EntityCacheKey("testPlatform", "testNetwork")).unsafeRunSync() shouldBe Some(
          CacheEntry(0, List())
        )
      }

      "init attribute values cache" in {
        val attributeValuesCache: AttributeValuesCache =
          Map(AttributeValueCacheKey("platform", "table", "column") -> CacheEntry(0L, RadixTree[String, String]()))
        sut.fillAttributeValuesCache(attributeValuesCache).unsafeRunSync()

        sut
          .getAttributeValues(
            AttributeValueCacheKey("not valid", "not valid", "not valid either")
          )
          .unsafeRunSync() shouldBe None

        val CacheEntry(_, result) = sut
          .getAttributeValues(
            AttributeValueCacheKey("platform", "table", "column")
          )
          .unsafeRunSync()
          .value
        result.values.toList shouldBe List.empty
      }

      "insert/update values in entities cache" in {
        val emptyEntitiesCache: EntitiesCache =
          Map(EntityCacheKey("testPlatform", "testNetwork") -> CacheEntry(0L, List()))
        val entitiesList = List(Entity("a", "b", 0))
        val updatedEntityList = List(Entity("x", "y", 0))
        sut.fillEntitiesCache(emptyEntitiesCache).unsafeRunSync()

        // insert
        sut.putEntities(EntityCacheKey("testPlatform", "differentTestNetwork"), entitiesList).unsafeRunSync()
        val CacheEntry(_, insertResult) =
          sut.getEntities(EntityCacheKey("testPlatform", "differentTestNetwork")).unsafeRunSync().value
        insertResult shouldBe entitiesList

        // update
        sut.putEntities(EntityCacheKey("testPlatform", "differentTestNetwork"), updatedEntityList).unsafeRunSync()
        val CacheEntry(_, updateResult) =
          sut.getEntities(EntityCacheKey("testPlatform", "differentTestNetwork")).unsafeRunSync().value
        updateResult shouldBe updatedEntityList
        sut.getAllEntities.unsafeRunSync().mapValues(_.value) shouldBe Map(
          EntityCacheKey("testPlatform", "testNetwork") -> List(),
          EntityCacheKey("testPlatform", "differentTestNetwork") -> List(Entity("x", "y", 0, None))
        )
      }

      "insert/update values in attributes cache" in {
        val emptyAttributesCache: AttributesCache =
          Map(AttributeCacheKey("testPlatform", "testEntity") -> CacheEntry(0L, List()))
        val attributesList = List(Attribute("a", "b", DataType.String, None, KeyType.NonKey, "c"))
        val updatedAttributesList = List(Attribute("x", "y", DataType.String, None, KeyType.NonKey, "z"))
        sut.fillAttributesCache(emptyAttributesCache).unsafeRunSync()

        // insert
        sut.putAttributes(AttributeCacheKey("testPlatform", "differentTestEntity"), attributesList).unsafeRunSync()
        val CacheEntry(_, insertResult) =
          sut.getAttributes(AttributeCacheKey("testPlatform", "differentTestEntity")).unsafeRunSync().value
        insertResult shouldBe attributesList

        // update
        sut
          .putAttributes(AttributeCacheKey("testPlatform", "differentTestEntity"), updatedAttributesList)
          .unsafeRunSync()
        val CacheEntry(_, updateResult) =
          sut.getAttributes(AttributeCacheKey("testPlatform", "differentTestEntity")).unsafeRunSync().get
        updateResult shouldBe updatedAttributesList
        sut.getAllAttributes.unsafeRunSync().mapValues(_.value) shouldBe
          Map(
            AttributeCacheKey("testPlatform", "testEntity") -> List(),
            AttributeCacheKey("testPlatform", "differentTestEntity") -> List(
                  Attribute("x", "y", DataType.String, None, KeyType.NonKey, "z")
                )
          )
      }

      "insert/update values in attribute values cache" in {
        val emptyAttributeValuesCache: AttributeValuesCache =
          Map(AttributeValueCacheKey("platform", "table", "column") -> CacheEntry(0L, RadixTree[String, String]()))
        val attributeValuesTree = RadixTree[String, String]("a" -> "a")
        val updatedAttributeValuesTree = RadixTree[String, String]("b" -> "b")
        sut.fillAttributeValuesCache(emptyAttributeValuesCache).unsafeRunSync()

        // insert
        sut
          .putAttributeValues(AttributeValueCacheKey("platform", "table2", "column2"), attributeValuesTree)
          .unsafeRunSync()
        val CacheEntry(_, insertResult) =
          sut.getAttributeValues(AttributeValueCacheKey("platform", "table2", "column2")).unsafeRunSync().get
        insertResult.values.toList shouldBe List("a")

        // update
        sut
          .putAttributeValues(AttributeValueCacheKey("platform", "table2", "column2"), updatedAttributeValuesTree)
          .unsafeRunSync()
        val CacheEntry(_, updateResult) =
          sut.getAttributeValues(AttributeValueCacheKey("platform", "table2", "column2")).unsafeRunSync().get
        updateResult.values.toList shouldBe List("b")
      }
    }

}
