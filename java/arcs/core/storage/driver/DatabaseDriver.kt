/*
 * Copyright 2020 Google LLC.
 *
 * This code may only be used under the BSD style license found at
 * http://polymer.github.io/LICENSE.txt
 *
 * Code distributed by Google as part of this project is also subject to an additional IP rights
 * grant found at
 * http://polymer.github.io/PATENTS.txt
 */

package arcs.core.storage.driver

import androidx.annotation.VisibleForTesting
import arcs.core.common.Referencable
import arcs.core.crdt.CrdtEntity
import arcs.core.crdt.CrdtOperation
import arcs.core.crdt.CrdtSet
import arcs.core.crdt.CrdtSingleton
import arcs.core.crdt.VersionMap
import arcs.core.crdt.toCrdtEntityData
import arcs.core.data.RawEntity
import arcs.core.data.Schema
import arcs.core.data.util.ReferencableList
import arcs.core.storage.Driver
import arcs.core.storage.RawReference
import arcs.core.storage.StorageKey
import arcs.core.storage.database.Database
import arcs.core.storage.database.DatabaseClient
import arcs.core.storage.database.DatabaseData
import arcs.core.storage.database.DatabaseOp
import arcs.core.storage.database.ReferenceWithVersion
import arcs.core.storage.driver.DatabaseDriverProvider.getDatabaseInfo
import arcs.core.storage.referencemode.ReferenceModeStorageKey
import arcs.core.storage.toReference
import arcs.core.util.Random
import arcs.core.util.TaggedLog
import arcs.flags.BuildFlagDisabledError
import arcs.flags.BuildFlags
import kotlin.reflect.KClass

/** [Driver] implementation capable of managing data stored in a SQL database. */
@Suppress("RemoveExplicitTypeArguments")
class DatabaseDriver<Data : Any>(
  override val storageKey: StorageKey,
  override val dataClass: KClass<Data>,
  private val schemaLookup: (String) -> Schema?,
  /* internal */
  val database: Database
) : Driver<Data>, DatabaseClient {
  /* internal */ var receiver: (suspend (data: Data, version: Int) -> Unit)? = null

  /* internal */ var clientId: Int = -1

  private val entitySchemaHash = checkNotNull(storageKey.getDatabaseInfo()?.entitySchemaHash) {
    "StorageKey of type ${storageKey.protocol} not supported by DatabaseDriver."
  }

  private val schema: Schema
    get() = checkNotNull(schemaLookup(entitySchemaHash)) {
      "Schema not found for hash: $entitySchemaHash"
    }

  private val containerStorageKey: StorageKey
    get() = (storageKey as? ReferenceModeStorageKey)?.storageKey ?: storageKey

  private val backingStorageKey: StorageKey
    get() = (storageKey as? ReferenceModeStorageKey)?.backingKey ?: storageKey

  // TODO(#5551): Consider including a hash of the toString info in log prefix.
  private val log = TaggedLog { "DatabaseDriver" }

  override var token: String? = null
    private set

  /* internal */
  suspend fun register(): DatabaseDriver<Data> = apply {
    clientId = database.addClient(this)

    log.debug { "Registered with clientId = $clientId" }
  }

  override suspend fun registerReceiver(
    token: String?,
    receiver: suspend (data: Data, version: Int) -> Unit
  ) {
    this.receiver = receiver
    val (pendingReceiverData, pendingReceiverVersion) = getDatabaseData()

    if (pendingReceiverData == null || pendingReceiverVersion == null) return

    log.verbose {
      """
                registerReceiver($token) - calling receiver(
                    $pendingReceiverData,
                    $pendingReceiverVersion
                )
      """.trimIndent()
    }
    receiver(pendingReceiverData, pendingReceiverVersion)
  }

  override suspend fun close() {
    receiver = null
    database.removeClient(clientId)
  }

  override suspend fun applyOps(ops: List<CrdtOperation>) {
    if (!BuildFlags.WRITE_ONLY_STORAGE_STACK) {
      throw BuildFlagDisabledError("WRITE_ONLY_STORAGE_STACK")
    }
    ops.forEach {
      val op = requireNotNull(it as? CrdtSet.IOperation<*>) {
        "Only CrdtSet operations are supported, got $it."
      }
      when (op) {
        is CrdtSet.Operation.Add<*> -> {
          val added = requireNotNull(op.added as? RawEntity) {
            "Only CrdtSet.IOperation<RawEntity> are supported, got $op."
          }
          // Send the new entity to the database at version 1. This does not support entity
          // mutations.
          val entityData = DatabaseData.Entity(added, schema, 1, ENTITIES_VERSION_MAP)
          // Set an empty version map to work around b/181057055. When that bug is fixed, we can use
          // ENTITIES_VERSION_MAP for the reference as well.
          val reference = op.added.toReference(backingStorageKey, VersionMap())
          database.insertOrUpdate(reference.referencedStorageKey(), entityData, clientId)
          database.applyOp(
            containerStorageKey,
            DatabaseOp.AddToCollection(reference, schema),
            clientId
          )
        }
        is CrdtSet.Operation.Remove<*> ->
          database.applyOp(
            containerStorageKey,
            DatabaseOp.RemoveFromCollection(op.removed, schema),
            clientId
          )
        is CrdtSet.Operation.Clear<*> ->
          database.applyOp(containerStorageKey, DatabaseOp.ClearCollection(schema), clientId)
        else -> throw UnsupportedOperationException("Unsupported operation $op")
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  override suspend fun send(data: Data, version: Int): Boolean {
    log.verbose {
      """
                send(
                    $data,
                    $version
                )
      """.trimIndent()
    }

    // Prep the data for storage.
    val databaseData = when (data) {
      is CrdtEntity.Data -> DatabaseData.Entity(
        data.toRawEntity(),
        schema,
        version,
        data.versionMap
      )
      is CrdtSingleton.Data<*> -> {
        val referenceData = requireNotNull(data as? CrdtSingleton.Data<RawReference>) {
          "Data must be CrdtSingleton.Data<RawReference>"
        }
        // Use consumerView logic to extract the item from the crdt.
        val id = CrdtSingleton.createWithData(referenceData).consumerView?.id
        val item = id?.let { referenceData.values[it] }
        DatabaseData.Singleton(
          item?.let { ReferenceWithVersion(it.value, it.versionMap) },
          schema,
          version,
          referenceData.versionMap
        )
      }
      is CrdtSet.Data<*> -> {
        val referenceData = requireNotNull(data as? CrdtSet.Data<RawReference>) {
          "Data must be CrdtSet.Data<RawReference>"
        }
        DatabaseData.Collection(
          referenceData.values.values.map {
            ReferenceWithVersion(it.value, it.versionMap)
          }.toSet(),
          schema,
          version,
          referenceData.versionMap
        )
      }
      else -> throw UnsupportedOperationException(
        "Unsupported type for DatabaseDriver: ${data::class}"
      )
    }

    // Store the prepped data.
    return database.insertOrUpdate(storageKey, databaseData, clientId)
  }

  override suspend fun onDatabaseUpdate(
    data: DatabaseData,
    version: Int,
    originatingClientId: Int?
  ) {
    if (originatingClientId == clientId) return

    // Convert the raw DatabaseData into the appropriate CRDT data model
    val actualData = data.toCrdtData<Data>()

    log.verbose {
      """
                onDatabaseUpdate(
                    $data,
                    version: $version,
                    originatingClientId: $originatingClientId
                )
      """.trimIndent()
    }

    // Let the receiver know about it.
    bumpToken()
    receiver?.invoke(actualData, version)
  }

  override suspend fun onDatabaseDelete(originatingClientId: Int?) {
    if (originatingClientId == clientId) return

    val (dbData, dbVersion) = getDatabaseData()

    log.debug { "onDatabaseDelete(originatingClientId: $originatingClientId)" }
    bumpToken()
    if (dbData != null && dbVersion != null) {
      receiver?.invoke(dbData, dbVersion)
    }
  }

  override fun toString(): String = "DatabaseDriver($storageKey, $clientId)"

  private fun bumpToken() {
    token = Random.nextInt().toString()
  }

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  suspend fun getDatabaseData(): Pair<Data?, Int?> {
    var dataAndVersion: Pair<Data?, Int?> = null to null
    database.get(
      storageKey,
      when (dataClass) {
        CrdtEntity.Data::class -> DatabaseData.Entity::class
        CrdtSingleton.DataImpl::class -> DatabaseData.Singleton::class
        CrdtSet.DataImpl::class -> DatabaseData.Collection::class
        else -> throw IllegalStateException("Illegal dataClass: $dataClass")
      },
      schema
    )?.also {
      dataAndVersion = it.toCrdtData<Data>() to it.databaseVersion
    }
    return dataAndVersion
  }

  override suspend fun clone(): Driver<Data> {
    val clone = DatabaseDriver(storageKey, dataClass, schemaLookup, database)
    clone.register()
    return clone
  }

  companion object {
    // We use a fixed version map when generating entities. Even if we don't modify the entity, we
    // need to have a non-empty version map to be compatible with ReferenceModeStore (which converts
    // the entities to crdt models). Any non-empty version map should work here.
    val ENTITIES_VERSION_MAP = VersionMap("e" to 1)
  }
}

@Suppress("UNCHECKED_CAST")
private fun <Data> DatabaseData.toCrdtData() = when (this) {
  is DatabaseData.Singleton -> value.toCrdtSingletonData(versionMap)
  is DatabaseData.Collection -> values.toCrdtSetData(versionMap)
  is DatabaseData.Entity -> rawEntity.toCrdtEntityData(versionMap) { it.toCrdtEntityReference() }
} as Data

/** Converts a [Set] of [RawReference]s into a [CrdtSet.Data] of those [RawReference]s. */
private fun Set<ReferenceWithVersion>.toCrdtSetData(
  versionMap: VersionMap
): CrdtSet.Data<RawReference> {
  return CrdtSet.DataImpl(
    versionMap.copy(),
    this.associateBy { it.rawReference.id }
      .mapValues { CrdtSet.DataValue(it.value.versionMap, it.value.rawReference) }
      .toMutableMap()
  )
}

/** Converts a nullable [RawReference] into a [CrdtSingleton.Data]. */
private fun ReferenceWithVersion?.toCrdtSingletonData(
  versionMap: VersionMap
): CrdtSingleton.Data<RawReference> {
  if (this == null) return CrdtSingleton.DataImpl(versionMap.copy())
  return CrdtSingleton.DataImpl(
    versionMap.copy(),
    mutableMapOf(this.rawReference.id to CrdtSet.DataValue(this.versionMap, this.rawReference))
  )
}

// We represent field data differently at different levels:
// * Users see Entities with language-specific types for fields
// * These get converted to RawEntities with Referencable fields
// * CRDTEntities all have CrdtEntity.Reference typed fields
// * The Database takes a fourth representation
//
// For CrdtEntity structures, Most Referencables are converted to
// strings wrapped in References, and the raw string is the DB Data
// layer. Inline entities and lists, however, wrap the Referencable
// structure in the Reference directly, and then unwrap it again for
// the DB layer (which understands these structures and deals with them).
// We did it this way because we didn't want to try and encode list o
// entity data in a string.
//
// This function deals with going in the opposite direction - that is,
// taking DB Data and converting it into a Reference with the right upstream
// behaviour.
private fun Referencable.toCrdtEntityReference(): CrdtEntity.Reference {
  return when (this) {
    is RawReference -> this
    is RawEntity -> CrdtEntity.Reference.wrapReferencable(this)
    is ReferencableList<*> -> CrdtEntity.Reference.wrapReferencable(this)
    else -> CrdtEntity.Reference.buildReference(this)
  }
}
