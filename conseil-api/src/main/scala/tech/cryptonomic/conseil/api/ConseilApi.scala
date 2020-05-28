package tech.cryptonomic.conseil.api

import java.util.UUID

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import cats.effect.{ContextShift, IO}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.typesafe.scalalogging.LazyLogging
import tech.cryptonomic.conseil.api.config.ConseilAppConfig.CombinedConfiguration
import tech.cryptonomic.conseil.api.directives.{EnableCORSDirectives, RecordingDirectives, ValidatingDirectives}
import tech.cryptonomic.conseil.api.metadata.{AttributeValuesCacheConfiguration, MetadataService, UnitTransformation}
import tech.cryptonomic.conseil.api.routes.Docs
import tech.cryptonomic.conseil.api.routes.info.AppInfo
import tech.cryptonomic.conseil.api.routes.platform.data.ApiDataRoutes
import tech.cryptonomic.conseil.api.routes.platform.discovery.{GenericPlatformDiscoveryOperations, PlatformDiscovery}
import tech.cryptonomic.conseil.api.routes.platform.{Api, TezosApi}
import tech.cryptonomic.conseil.api.security.Security
import tech.cryptonomic.conseil.common.cache.MetadataCaching
import tech.cryptonomic.conseil.common.config.Platforms
import tech.cryptonomic.conseil.common.config.Platforms.BlockchainPlatform
import tech.cryptonomic.conseil.common.sql.DatabaseMetadataOperations
import tech.cryptonomic.conseil.common.util.DatabaseUtil

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object ConseilApi {
  def create(config: CombinedConfiguration)(implicit system: ActorSystem): ConseilApi = new ConseilApi(config)
}

class ConseilApi(config: CombinedConfiguration)(implicit system: ActorSystem)
    extends EnableCORSDirectives
    with LazyLogging {

  private val transformation = new UnitTransformation(config.metadata)
  private val cacheOverrides = new AttributeValuesCacheConfiguration(config.metadata)

  implicit private val mat: ActorMaterializer = ActorMaterializer()
  implicit private val dispatcher: ExecutionContext = system.dispatcher
  implicit private val contextShift: ContextShift[IO] = IO.contextShift(dispatcher)

  config.nautilusCloud.foreach { ncc =>
    system.scheduler.schedule(ncc.delay, ncc.interval)(Security.updateKeys(ncc))
  }

  // this val is not lazy to force to fetch metadata and trigger logging at the start of the application
  private val metadataService =
    new MetadataService(config.platforms, transformation, cacheOverrides, ApiCache.cachedDiscoveryOperations)

  lazy val route: Route = {
    lazy val platformDiscovery = PlatformDiscovery(metadataService)

    lazy val routeDirectives = new RecordingDirectives
    lazy val securityDirectives = new ValidatingDirectives(config.security)

    import routeDirectives._
    import securityDirectives._

    concat(
      pathPrefix("docs") {
        pathEndOrSingleSlash {
          getFromResource("web/index.html")
        }
      },
      pathPrefix("swagger-ui") {
        getFromResourceDirectory("web/swagger-ui/")
      },
      Docs.route,
      cors() {
        enableCORS {
          implicit val correlationId: UUID = UUID.randomUUID()
          handleExceptions(loggingExceptionHandler) {
            extractClientIP {
              ip =>
                recordResponseValues(ip)(mat, correlationId) {
                  timeoutHandler {
                    concat(
                      validateApiKey { _ =>
                        concat(
                          logRequest("Conseil", Logging.DebugLevel) {
                            AppInfo.route
                          },
                          logRequest("Metadata Route", Logging.DebugLevel) {
                            platformDiscovery.route
                          },
                          ApiCache
                            .cachedDataEndpoints(metadataService)
                            .map {
                              case (platform, routes) =>
                                logRequest(s"$platform Data Route", Logging.DebugLevel) {
                                  routes.getRoute ~ routes.postRoute
                                }
                            }
                            .reduce(_ ~ _)
                        )
                      },
                      options {
                        // Support for CORS pre-flight checks.
                        complete("Supported methods : GET and POST.")
                      }
                    )
                  }
                }
            }
          }
        }
      }
    )
  }

  /**
    * Object, which initializes and holds all of the APIs (blockchain-specific endpoints) in the map.
    *
    * Note that only APIs that are visible (configured via metadata config file)
    * will be initialized and eventually exposed.
    */
  private object ApiCache {
    private lazy val cache: Map[BlockchainPlatform, Api] = forVisiblePlatforms {
      case Platforms.Tezos => new TezosApi(config.metadata, config.server)
    }

    private val cacheOverrides = new AttributeValuesCacheConfiguration(config.metadata)
    private val metadataCaching = MetadataCaching.empty[IO].unsafeRunSync()
    private val metadataOperations: DatabaseMetadataOperations = new DatabaseMetadataOperations {
      override lazy val dbReadHandle = DatabaseUtil.conseilDb
    }

    lazy val cachedDiscoveryOperations: GenericPlatformDiscoveryOperations =
      new GenericPlatformDiscoveryOperations(
        metadataOperations,
        metadataCaching,
        cacheOverrides,
        config.server.cacheTTL,
        config.server.highCardinalityLimit
      )

    /**
      * Map, that contains list of available `ApiDataRoutes` accessed by platform name.
      * @see `tech.cryptonomic.conseil.common.config.Platforms` to get list of possible platforms.
      */
    lazy val cachedDataEndpoints: MetadataService => Map[String, ApiDataRoutes] = service =>
      cache.map {
        case (key, value) => key.name -> value.dataEndpoint(service)
      }

    private val visiblePlatforms =
      transformation.overridePlatforms(config.platforms.getPlatforms)

    private def forVisiblePlatforms(f: BlockchainPlatform => Api): Map[BlockchainPlatform, Api] =
      visiblePlatforms.map { platform =>
        val blockchainPlatform = BlockchainPlatform.fromString(platform.name)
        blockchainPlatform -> f(blockchainPlatform)
      }.toMap

    private val visibleNetworks = for {
      platform <- visiblePlatforms
      network <- transformation.overrideNetworks(platform.path, config.platforms.getNetworks(platform.name))
    } yield platform -> network

    cachedDiscoveryOperations.init(visibleNetworks).onComplete {
      case Failure(exception) => logger.error("Pre-caching metadata failed", exception)
      case Success(_) => logger.info("Pre-caching successful!")
    }

    cachedDiscoveryOperations.initAttributesCache(visibleNetworks).onComplete {
      case Failure(exception) => logger.error("Pre-caching attributes failed", exception)
      case Success(_) => logger.info("Pre-caching attributes successful!")
    }
  }

}