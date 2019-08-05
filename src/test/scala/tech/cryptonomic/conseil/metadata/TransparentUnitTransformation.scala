package tech.cryptonomic.conseil.metadata

import tech.cryptonomic.conseil.config.MetadataConfiguration
import tech.cryptonomic.conseil.generic.chain.PlatformDiscoveryTypes

/* UnitTransformation implementation for test purposes. It overrides nothing. */
object TransparentUnitTransformation extends UnitTransformation(MetadataConfiguration(Map.empty)) {
  override def overridePlatforms(
      platforms: List[PlatformDiscoveryTypes.Platform]
  ): List[PlatformDiscoveryTypes.Platform] = platforms

  override def overrideNetworks(
      platformPath: PlatformPath,
      networks: List[PlatformDiscoveryTypes.Network]
  ): List[PlatformDiscoveryTypes.Network] = networks

  override def overrideEntities(
      networkPath: NetworkPath,
      entities: List[PlatformDiscoveryTypes.Entity]
  ): List[PlatformDiscoveryTypes.Entity] = entities

  override def overrideAttributes(
      path: EntityPath,
      attributes: List[PlatformDiscoveryTypes.Attribute]
  ): List[PlatformDiscoveryTypes.Attribute] = attributes
}