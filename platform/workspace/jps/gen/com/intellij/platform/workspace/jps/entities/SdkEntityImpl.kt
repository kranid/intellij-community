// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.workspace.jps.entities

import com.intellij.platform.workspace.storage.*
import com.intellij.platform.workspace.storage.EntityInformation
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.EntityType
import com.intellij.platform.workspace.storage.GeneratedCodeApiVersion
import com.intellij.platform.workspace.storage.GeneratedCodeImplVersion
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.SymbolicEntityId
import com.intellij.platform.workspace.storage.WorkspaceEntity
import com.intellij.platform.workspace.storage.impl.ConnectionId
import com.intellij.platform.workspace.storage.impl.ModifiableWorkspaceEntityBase
import com.intellij.platform.workspace.storage.impl.WorkspaceEntityBase
import com.intellij.platform.workspace.storage.impl.WorkspaceEntityData
import com.intellij.platform.workspace.storage.impl.containers.MutableWorkspaceList
import com.intellij.platform.workspace.storage.impl.containers.toMutableWorkspaceList
import com.intellij.platform.workspace.storage.instrumentation.EntityStorageInstrumentation
import com.intellij.platform.workspace.storage.instrumentation.EntityStorageInstrumentationApi
import com.intellij.platform.workspace.storage.metadata.model.EntityMetadata
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import java.io.Serializable
import org.jetbrains.annotations.NonNls

@GeneratedCodeApiVersion(2)
@GeneratedCodeImplVersion(3)
open class SdkEntityImpl(private val dataSource: SdkEntityData) : SdkEntity, WorkspaceEntityBase(dataSource) {

  private companion object {


    private val connections = listOf<ConnectionId>(
    )

  }

  override val name: String
    get() = dataSource.name

  override val type: String
    get() = dataSource.type

  override val version: String?
    get() = dataSource.version

  override val homePath: VirtualFileUrl
    get() = dataSource.homePath

  override val roots: List<SdkRoot>
    get() = dataSource.roots

  override val additionalData: String
    get() = dataSource.additionalData

  override val entitySource: EntitySource
    get() = dataSource.entitySource

  override fun connectionIdList(): List<ConnectionId> {
    return connections
  }


  class Builder(result: SdkEntityData?) : ModifiableWorkspaceEntityBase<SdkEntity, SdkEntityData>(result), SdkEntity.Builder {
    constructor() : this(SdkEntityData())

    override fun applyToBuilder(builder: MutableEntityStorage) {
      if (this.diff != null) {
        if (existsInBuilder(builder)) {
          this.diff = builder
          return
        }
        else {
          error("Entity SdkEntity is already created in a different builder")
        }
      }

      this.diff = builder
      this.snapshot = builder
      addToBuilder()
      this.id = getEntityData().createEntityId()
      // After adding entity data to the builder, we need to unbind it and move the control over entity data to builder
      // Builder may switch to snapshot at any moment and lock entity data to modification
      this.currentEntityData = null

      index(this, "homePath", this.homePath)
      indexSdkRoots(roots)
      // Process linked entities that are connected without a builder
      processLinkedEntities(builder)
      checkInitialization() // TODO uncomment and check failed tests
    }

    private fun checkInitialization() {
      val _diff = diff
      if (!getEntityData().isEntitySourceInitialized()) {
        error("Field WorkspaceEntity#entitySource should be initialized")
      }
      if (!getEntityData().isNameInitialized()) {
        error("Field SdkEntity#name should be initialized")
      }
      if (!getEntityData().isTypeInitialized()) {
        error("Field SdkEntity#type should be initialized")
      }
      if (!getEntityData().isHomePathInitialized()) {
        error("Field SdkEntity#homePath should be initialized")
      }
      if (!getEntityData().isRootsInitialized()) {
        error("Field SdkEntity#roots should be initialized")
      }
      if (!getEntityData().isAdditionalDataInitialized()) {
        error("Field SdkEntity#additionalData should be initialized")
      }
    }

    override fun connectionIdList(): List<ConnectionId> {
      return connections
    }

    override fun afterModification() {
      val collection_roots = getEntityData().roots
      if (collection_roots is MutableWorkspaceList<*>) {
        collection_roots.cleanModificationUpdateAction()
      }
    }

    // Relabeling code, move information from dataSource to this builder
    override fun relabel(dataSource: WorkspaceEntity, parents: Set<WorkspaceEntity>?) {
      dataSource as SdkEntity
      if (this.entitySource != dataSource.entitySource) this.entitySource = dataSource.entitySource
      if (this.name != dataSource.name) this.name = dataSource.name
      if (this.type != dataSource.type) this.type = dataSource.type
      if (this.version != dataSource?.version) this.version = dataSource.version
      if (this.homePath != dataSource.homePath) this.homePath = dataSource.homePath
      if (this.roots != dataSource.roots) this.roots = dataSource.roots.toMutableList()
      if (this.additionalData != dataSource.additionalData) this.additionalData = dataSource.additionalData
      updateChildToParentReferences(parents)
    }

    private fun indexSdkRoots(sdkRoots: List<SdkRoot>) {
      val sdkRootList = sdkRoots.map { it.url }.toHashSet()
      index(this, "roots", sdkRootList)
    }


    override var entitySource: EntitySource
      get() = getEntityData().entitySource
      set(value) {
        checkModificationAllowed()
        getEntityData(true).entitySource = value
        changedProperty.add("entitySource")

      }

    override var name: String
      get() = getEntityData().name
      set(value) {
        checkModificationAllowed()
        getEntityData(true).name = value
        changedProperty.add("name")
      }

    override var type: String
      get() = getEntityData().type
      set(value) {
        checkModificationAllowed()
        getEntityData(true).type = value
        changedProperty.add("type")
      }

    override var version: String?
      get() = getEntityData().version
      set(value) {
        checkModificationAllowed()
        getEntityData(true).version = value
        changedProperty.add("version")
      }

    override var homePath: VirtualFileUrl
      get() = getEntityData().homePath
      set(value) {
        checkModificationAllowed()
        getEntityData(true).homePath = value
        changedProperty.add("homePath")
        val _diff = diff
        if (_diff != null) index(this, "homePath", value)
      }

    private val rootsUpdater: (value: List<SdkRoot>) -> Unit = { value ->

      val _diff = diff
      if (_diff != null) {
        indexSdkRoots(value)
      }

      changedProperty.add("roots")
    }
    override var roots: MutableList<SdkRoot>
      get() {
        val collection_roots = getEntityData().roots
        if (collection_roots !is MutableWorkspaceList) return collection_roots
        if (diff == null || modifiable.get()) {
          collection_roots.setModificationUpdateAction(rootsUpdater)
        }
        else {
          collection_roots.cleanModificationUpdateAction()
        }
        return collection_roots
      }
      set(value) {
        checkModificationAllowed()
        getEntityData(true).roots = value
        rootsUpdater.invoke(value)
      }

    override var additionalData: String
      get() = getEntityData().additionalData
      set(value) {
        checkModificationAllowed()
        getEntityData(true).additionalData = value
        changedProperty.add("additionalData")
      }

    override fun getEntityClass(): Class<SdkEntity> = SdkEntity::class.java
  }
}

class SdkEntityData : WorkspaceEntityData.WithCalculableSymbolicId<SdkEntity>() {
  lateinit var name: String
  lateinit var type: String
  var version: String? = null
  lateinit var homePath: VirtualFileUrl
  lateinit var roots: MutableList<SdkRoot>
  lateinit var additionalData: String

  internal fun isNameInitialized(): Boolean = ::name.isInitialized
  internal fun isTypeInitialized(): Boolean = ::type.isInitialized
  internal fun isHomePathInitialized(): Boolean = ::homePath.isInitialized
  internal fun isRootsInitialized(): Boolean = ::roots.isInitialized
  internal fun isAdditionalDataInitialized(): Boolean = ::additionalData.isInitialized

  override fun wrapAsModifiable(diff: MutableEntityStorage): WorkspaceEntity.Builder<SdkEntity> {
    val modifiable = SdkEntityImpl.Builder(null)
    modifiable.diff = diff
    modifiable.snapshot = diff
    modifiable.id = createEntityId()
    return modifiable
  }

  @OptIn(EntityStorageInstrumentationApi::class)
  override fun createEntity(snapshot: EntityStorageInstrumentation): SdkEntity {
    val entityId = createEntityId()
    return snapshot.initializeEntity(entityId) {
      val entity = SdkEntityImpl(this)
      entity.snapshot = snapshot
      entity.id = entityId
      entity
    }
  }

  override fun getMetadata(): EntityMetadata {
    return MetadataStorageImpl.getMetadataByTypeFqn("com.intellij.platform.workspace.jps.entities.SdkEntity") as EntityMetadata
  }

  override fun clone(): SdkEntityData {
    val clonedEntity = super.clone()
    clonedEntity as SdkEntityData
    clonedEntity.roots = clonedEntity.roots.toMutableWorkspaceList()
    return clonedEntity
  }

  override fun symbolicId(): SymbolicEntityId<*> {
    return SdkId(name, type)
  }

  override fun getEntityInterface(): Class<out WorkspaceEntity> {
    return SdkEntity::class.java
  }

  override fun serialize(ser: EntityInformation.Serializer) {
  }

  override fun deserialize(de: EntityInformation.Deserializer) {
  }

  override fun createDetachedEntity(parents: List<WorkspaceEntity>): WorkspaceEntity {
    return SdkEntity(name, type, homePath, roots, additionalData, entitySource) {
      this.version = this@SdkEntityData.version
    }
  }

  override fun getRequiredParents(): List<Class<out WorkspaceEntity>> {
    val res = mutableListOf<Class<out WorkspaceEntity>>()
    return res
  }

  override fun equals(other: Any?): Boolean {
    if (other == null) return false
    if (this.javaClass != other.javaClass) return false

    other as SdkEntityData

    if (this.entitySource != other.entitySource) return false
    if (this.name != other.name) return false
    if (this.type != other.type) return false
    if (this.version != other.version) return false
    if (this.homePath != other.homePath) return false
    if (this.roots != other.roots) return false
    if (this.additionalData != other.additionalData) return false
    return true
  }

  override fun equalsIgnoringEntitySource(other: Any?): Boolean {
    if (other == null) return false
    if (this.javaClass != other.javaClass) return false

    other as SdkEntityData

    if (this.name != other.name) return false
    if (this.type != other.type) return false
    if (this.version != other.version) return false
    if (this.homePath != other.homePath) return false
    if (this.roots != other.roots) return false
    if (this.additionalData != other.additionalData) return false
    return true
  }

  override fun hashCode(): Int {
    var result = entitySource.hashCode()
    result = 31 * result + name.hashCode()
    result = 31 * result + type.hashCode()
    result = 31 * result + version.hashCode()
    result = 31 * result + homePath.hashCode()
    result = 31 * result + roots.hashCode()
    result = 31 * result + additionalData.hashCode()
    return result
  }

  override fun hashCodeIgnoringEntitySource(): Int {
    var result = javaClass.hashCode()
    result = 31 * result + name.hashCode()
    result = 31 * result + type.hashCode()
    result = 31 * result + version.hashCode()
    result = 31 * result + homePath.hashCode()
    result = 31 * result + roots.hashCode()
    result = 31 * result + additionalData.hashCode()
    return result
  }
}
