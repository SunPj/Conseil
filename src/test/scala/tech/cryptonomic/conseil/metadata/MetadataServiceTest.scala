package tech.cryptonomic.conseil.metadata

import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Matchers, WordSpec}
import tech.cryptonomic.conseil.config.Platforms.{PlatformsConfiguration, TezosConfiguration, TezosNodeConfiguration}
import tech.cryptonomic.conseil.config.Types.PlatformName
import tech.cryptonomic.conseil.config.{AttributeConfiguration, EntityConfiguration, MetadataOverridesConfiguration, NetworkConfiguration, PlatformConfiguration, Platforms}
import tech.cryptonomic.conseil.generic.chain.PlatformDiscoveryTypes.DataType.Int
import tech.cryptonomic.conseil.generic.chain.PlatformDiscoveryTypes.KeyType.NonKey
import tech.cryptonomic.conseil.generic.chain.PlatformDiscoveryTypes.{Attribute, Entity, Network, Platform}
import tech.cryptonomic.conseil.tezos.TezosPlatformDiscoveryOperations

import scala.concurrent.Future.successful

class MetadataServiceTest extends WordSpec with Matchers with ScalatestRouteTest with MockFactory with ScalaFutures {

  "The metadata service" should {

    val tezosPlatformDiscoveryOperations = stub[TezosPlatformDiscoveryOperations]

    val sut = (metadataOverridesConfiguration: Map[PlatformName, PlatformConfiguration]) => new MetadataService(
      PlatformsConfiguration(Map(Platforms.Tezos -> List(
        TezosConfiguration("mainnet",
          TezosNodeConfiguration("tezos-host", 123, "https://"))))),
      new UnitTransformation(MetadataOverridesConfiguration(metadataOverridesConfiguration)),
      tezosPlatformDiscoveryOperations)

    "fetch the list of supported platforms" in {
      sut(Map.empty).getPlatforms shouldBe List(Platform("tezos", "Tezos"))
    }

    "override the display name for a platform" in {
      sut(Map("tezos" -> PlatformConfiguration(Some("overwritten name"), None))).getPlatforms shouldBe List(Platform("tezos", "overwritten name"))
    }

    "filter out disabled platform" in {
      sut(Map("tezos" -> PlatformConfiguration(None, Some(false)))).getPlatforms shouldBe List.empty
    }

    "fetch the list of supported networks" in {
      sut(Map.empty).getNetworks(PlatformPath("tezos")) shouldBe Some(List(Network("mainnet", "Mainnet", "tezos", "mainnet")))
    }

    "override the display name for a network" in {
      // given
      val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, None, Map("mainnet" -> NetworkConfiguration(Some("overwritten name"), None))))

      // when
      val result = sut(overriddenConfiguration).getNetworks(PlatformPath("tezos"))

      // then
      result shouldBe Some(List(Network("mainnet", "overwritten name", "tezos", "mainnet")))
    }

    "filter out a hidden network" in {
      // given
      val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, None, Map("mainnet" -> NetworkConfiguration(None, Some(false)))))

      // when
      val result = sut(overriddenConfiguration).getNetworks(PlatformPath("tezos"))

      // then
      result shouldBe Some(List.empty)
    }

    "return None when fetching network for a non existing platform" in {
      // when
      val result = sut(Map.empty).getNetworks(PlatformPath("non-existing-platform"))

      // then
      result shouldBe None
    }

    "return None when fetching networks for a hidden platform" in {
      // given
      val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, Some(false)))

      // when
      val result = sut(overriddenConfiguration).getNetworks(PlatformPath("tezos"))

      // then
      result shouldBe None
    }

    "fetch the list of supported entities" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))

      // when
      val result = sut(Map.empty).getEntities(NetworkPath("mainnet", PlatformPath("tezos"))).futureValue

      // then
      result shouldBe Some(List(Entity("entity", "entity-name", 0)))
    }

    "override the display name for an entity" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))

      val overwrittenConfiguration = Map("tezos" ->
        PlatformConfiguration(None, None, Map("mainnet" ->
          NetworkConfiguration(None, None, Map("entity" ->
            EntityConfiguration(Some("overwritten name"), None))))))

      // when
      val result = sut(overwrittenConfiguration).getEntities(NetworkPath("mainnet", PlatformPath("tezos"))).futureValue

      // then
      result shouldBe Some(List(Entity("entity", "overwritten name", 0)))
    }

    "filter out a hidden entity" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))

      val overwrittenConfiguration = Map("tezos" ->
        PlatformConfiguration(None, None, Map("mainnet" ->
          NetworkConfiguration(None, None, Map("entity" ->
            EntityConfiguration(None, Some(false)))))))

      // when
      val result = sut(overwrittenConfiguration).getEntities(NetworkPath("mainnet", PlatformPath("tezos"))).futureValue

      // then
      result shouldBe Some(List.empty)
    }

    "return None when fetching entities for a non existing platform" in {
      // when
      val result = sut(Map.empty).getEntities(NetworkPath("mainnet", PlatformPath("non-existing-platform"))).futureValue

      // then
      result shouldBe None
    }

    "return None when fetching entities for a hidden platform" in {
      // given
      val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, Some(false)))

      // when
      val result = sut(overriddenConfiguration).getEntities(NetworkPath("tezos", PlatformPath("mainnet"))).futureValue

      // then
      result shouldBe None
    }

    "return None when fetching entities for a non existing network" in {
      // when
      val result = sut(Map.empty).getEntities(NetworkPath("non-existing-network", PlatformPath("tezos"))).futureValue

      // then
      result shouldBe None
    }

    "return None when fetching entities for a hidden network" in {
      // given
      val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, None, Map("mainnet" -> NetworkConfiguration(None, Some(false)))))

      // when
      val result = sut(overriddenConfiguration).getEntities(NetworkPath("tezos", PlatformPath("mainnet"))).futureValue

      // then
      result shouldBe None
    }

    "fetch the list of supported attributes" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))
      (tezosPlatformDiscoveryOperations.getTableAttributes _).when("entity").returns(successful(Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))))

      // when
      val result = sut(Map.empty).getTableAttributes(EntityPath("entity", NetworkPath("mainnet", PlatformPath("tezos")))).futureValue

      // then
      result shouldBe Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))
    }

    "override the display name for an attribute" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))
      (tezosPlatformDiscoveryOperations.getTableAttributes _).when("entity").returns(successful(Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))))

      val overwrittenConfiguration = Map("tezos" ->
        PlatformConfiguration(None, None, Map("mainnet" ->
          NetworkConfiguration(None, None, Map("entity" ->
            EntityConfiguration(None, None, Map("attribute" ->
              AttributeConfiguration(Some("overwritten-name"), None))))))))

      // when
      val result = sut(overwrittenConfiguration).getTableAttributes(EntityPath("entity", NetworkPath("mainnet", PlatformPath("tezos")))).futureValue

      // then
      result shouldBe Some(List(Attribute("attribute", "overwritten-name", Int, None, NonKey, "entity")))
    }

    "filter out a hidden attribute" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))
      (tezosPlatformDiscoveryOperations.getTableAttributes _).when("entity").returns(successful(Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))))

      val overwrittenConfiguration = Map("tezos" ->
        PlatformConfiguration(None, None, Map("mainnet" ->
          NetworkConfiguration(None, None, Map("entity" ->
            EntityConfiguration(None, None, Map("attribute" ->
              AttributeConfiguration(None, Some(false)))))))))

      // when
      val result = sut(overwrittenConfiguration).getTableAttributes(EntityPath("entity", NetworkPath("mainnet", PlatformPath("tezos")))).futureValue

      // then
      result shouldBe Some(List.empty)
    }

    "return None when fetching attributes for a non existing platform" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))
      (tezosPlatformDiscoveryOperations.getTableAttributes _).when("entity").returns(successful(Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))))

      // when
      val result = sut(Map.empty).getTableAttributes(EntityPath("entity", NetworkPath("mainnet", PlatformPath("non-existing-platform")))).futureValue

      // then
      result shouldBe None
    }

    "return None when fetching attributes for a hidden platform" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))
      (tezosPlatformDiscoveryOperations.getTableAttributes _).when("entity").returns(successful(Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))))

      val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, Some(false)))

      // when
      val result = sut(overriddenConfiguration).getTableAttributes(EntityPath("entity", NetworkPath("mainnet", PlatformPath("tezos")))).futureValue

      // then
      result shouldBe None
    }

    "return None when fetching attributes for a non existing network" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))
      (tezosPlatformDiscoveryOperations.getTableAttributes _).when("entity").returns(successful(Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))))

      // when
      val result = sut(Map.empty).getTableAttributes(EntityPath("entity", NetworkPath("non-existing-network", PlatformPath("tezos")))).futureValue

      // then
      result shouldBe None
    }

    "return None when fetching attributes for a hidden network" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))
      (tezosPlatformDiscoveryOperations.getTableAttributes _).when("entity").returns(successful(Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))))

      // given
      val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, None, Map("mainnet" -> NetworkConfiguration(None, Some(false)))))

      // when
      val result = sut(overriddenConfiguration).getTableAttributes(EntityPath("entity", NetworkPath("mainnet", PlatformPath("tezos")))).futureValue

      // then
      result shouldBe None
    }

    "return None when fetching attributes for a non existing entity" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List.empty))
      (tezosPlatformDiscoveryOperations.getTableAttributes _).when("entity").returns(successful(Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))))

      // when
      val result = sut(Map.empty).getTableAttributes(EntityPath("entity", NetworkPath("mainnet", PlatformPath("tezos")))).futureValue

      // then
      result shouldBe None
    }

    "return None when fetching attributes for a hidden entity" in {
      // given
      (tezosPlatformDiscoveryOperations.getEntities _).when().returns(successful(List(Entity("entity", "entity-name", 0))))
      (tezosPlatformDiscoveryOperations.getTableAttributes _).when("entity").returns(successful(Some(List(Attribute("attribute", "attribute-name", Int, None, NonKey, "entity")))))

      val overriddenConfiguration = Map("tezos" -> PlatformConfiguration(None, None, Map("mainnet" -> NetworkConfiguration(None, Some(false)))))

      // when
      val result = sut(overriddenConfiguration).getTableAttributes(EntityPath("entity", NetworkPath("mainnet", PlatformPath("tezos")))).futureValue

      // then
      result shouldBe None
    }

  }
}