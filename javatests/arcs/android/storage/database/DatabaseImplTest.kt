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

package arcs.android.storage.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import arcs.android.common.forSingleResult
import arcs.android.common.getNullableBoolean
import arcs.android.common.map
import arcs.android.storage.database.DatabaseImpl.Companion.DATABASE_CRDT_ACTOR
import arcs.android.storage.database.DatabaseImpl.FieldClass
import arcs.android.util.testutil.AndroidLogRule
import arcs.core.common.Referencable
import arcs.core.crdt.VersionMap
import arcs.core.data.FieldType
import arcs.core.data.PrimitiveType
import arcs.core.data.RawEntity
import arcs.core.data.Schema
import arcs.core.data.SchemaFields
import arcs.core.data.SchemaRegistry
import arcs.core.data.util.ReferencablePrimitive
import arcs.core.data.util.toReferencable
import arcs.core.entity.testutil.FixtureEntities
import arcs.core.entity.testutil.FixtureEntity
import arcs.core.storage.RawReference
import arcs.core.storage.StorageKey
import arcs.core.storage.StorageKeyManager
import arcs.core.storage.database.DatabaseClient
import arcs.core.storage.database.DatabaseData
import arcs.core.storage.database.DatabaseOp
import arcs.core.storage.database.ReferenceWithVersion
import arcs.core.storage.testutil.DummyStorageKey
import arcs.core.storage.testutil.DummyStorageKeyManager
import arcs.core.util.ArcsDuration
import arcs.core.util.guardedBy
import arcs.flags.BuildFlagDisabledError
import arcs.flags.BuildFlags
import arcs.flags.testing.BuildFlagsRule
import arcs.flags.testing.ParameterizedBuildFlags
import arcs.jvm.util.JvmTime
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class DatabaseImplTest(private val parameters: ParameterizedBuildFlags) {

  @get:Rule
  val rule = BuildFlagsRule.parameterized(parameters)

  @get:Rule
  val log = AndroidLogRule()

  @get:Rule
  val buildFlagsRule = BuildFlagsRule.create()

  private lateinit var database: DatabaseImpl
  private lateinit var db: SQLiteDatabase
  private val fixtureEntities = FixtureEntities()

  @Before
  fun setUp() {
    BuildFlags.WRITE_ONLY_STORAGE_STACK = true
    database = DatabaseImpl(
      ApplicationProvider.getApplicationContext(),
      DummyStorageKeyManager(),
      "test.sqlite3"
    )
    db = database.writableDatabase
    StorageKeyManager.GLOBAL_INSTANCE.addParser(DummyStorageKey)
    FixtureEntities.registerSchemas()
  }

  @After
  fun tearDown() {
    database.reset()
    database.close()
    StorageKeyManager.GLOBAL_INSTANCE.reset()
    SchemaRegistry.clearForTest()
  }

  @Test
  fun getTypeId_primitiveTypeIds() = runBlockingTest {
    assertThat(database.getTypeIdForTest(FieldType.Boolean))
      .isEqualTo(PrimitiveType.Boolean.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Number))
      .isEqualTo(PrimitiveType.Number.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Text))
      .isEqualTo(PrimitiveType.Text.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.BigInt))
      .isEqualTo(PrimitiveType.BigInt.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Byte))
      .isEqualTo(PrimitiveType.Byte.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Char))
      .isEqualTo(PrimitiveType.Char.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Double))
      .isEqualTo(PrimitiveType.Double.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Float))
      .isEqualTo(PrimitiveType.Float.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Instant))
      .isEqualTo(PrimitiveType.Instant.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Duration))
      .isEqualTo(PrimitiveType.Duration.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Int))
      .isEqualTo(PrimitiveType.Int.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Long))
      .isEqualTo(PrimitiveType.Long.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Short))
      .isEqualTo(PrimitiveType.Short.ordinal)
  }

  @Test
  fun getTypeId_nullablePrimitiveTypeIds() = runBlockingTest {
    assertThat(database.getTypeIdForTest(FieldType.Boolean.nullable()))
      .isEqualTo(PrimitiveType.Boolean.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Number.nullable()))
      .isEqualTo(PrimitiveType.Number.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Text.nullable()))
      .isEqualTo(PrimitiveType.Text.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.BigInt.nullable()))
      .isEqualTo(PrimitiveType.BigInt.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Byte.nullable()))
      .isEqualTo(PrimitiveType.Byte.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Char.nullable()))
      .isEqualTo(PrimitiveType.Char.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Double.nullable()))
      .isEqualTo(PrimitiveType.Double.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Float.nullable()))
      .isEqualTo(PrimitiveType.Float.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Instant.nullable()))
      .isEqualTo(PrimitiveType.Instant.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Duration.nullable()))
      .isEqualTo(PrimitiveType.Duration.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Int.nullable()))
      .isEqualTo(PrimitiveType.Int.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Long.nullable()))
      .isEqualTo(PrimitiveType.Long.ordinal)
    assertThat(database.getTypeIdForTest(FieldType.Short.nullable()))
      .isEqualTo(PrimitiveType.Short.ordinal)
  }

  @Test
  fun getTypeId_entityRef() = runBlockingTest {
    SchemaRegistry.register(Schema.EMPTY)

    val typeId = database.getTypeIdForTest(FieldType.EntityRef(Schema.EMPTY.hash))
    assertThat(typeId).isGreaterThan(PrimitiveType.values().size)
  }

  @Test
  fun getTypeId_nullableEntityRef() = runBlockingTest {
    SchemaRegistry.register(Schema.EMPTY)

    val typeId = database.getTypeIdForTest(FieldType.EntityRef(Schema.EMPTY.hash).nullable())
    assertThat(typeId).isGreaterThan(PrimitiveType.values().size)
  }

  @Test
  fun getTypeId_inlineEntity() = runBlockingTest {
    SchemaRegistry.register(Schema.EMPTY)

    val typeId = database.getTypeIdForTest(FieldType.InlineEntity(Schema.EMPTY.hash))
    assertThat(typeId).isGreaterThan(PrimitiveType.values().size)
  }

  @Test
  fun getTypeId_listOf() = runBlockingTest {
    SchemaRegistry.register(Schema.EMPTY)

    val entityRefTypeId =
      database.getTypeIdForTest(FieldType.ListOf(FieldType.EntityRef(Schema.EMPTY.hash)))
    assertThat(entityRefTypeId).isGreaterThan(PrimitiveType.values().size)

    val inlineEntityTypeId =
      database.getTypeIdForTest(FieldType.ListOf(FieldType.InlineEntity(Schema.EMPTY.hash)))
    assertThat(inlineEntityTypeId).isGreaterThan(PrimitiveType.values().size)

    assertWithMessage("ListOf reference/inline should have the same typeId for the same schema")
      .that(entityRefTypeId).isEqualTo(inlineEntityTypeId)

    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Boolean)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Boolean))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Number)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Number))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Text)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Text))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.BigInt)))
      .isEqualTo(database.getTypeIdForTest(FieldType.BigInt))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Byte)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Byte))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Char)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Char))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Double)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Double))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Float)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Float))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Instant)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Instant))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Duration)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Duration))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Int)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Int))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Long)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Long))
    assertThat(database.getTypeIdForTest(FieldType.ListOf(FieldType.Short)))
      .isEqualTo(database.getTypeIdForTest(FieldType.Short))
  }

  @Test
  fun getTypeId_entity_throwsWhenMissing() = runBlockingTest {
    val exception = assertFailsWith<NoSuchElementException> {
      database.getTypeIdForTest(FieldType.EntityRef("shouldnotexistanywhere"))
    }
    assertThat(exception).hasMessageThat().isEqualTo(
      "Schema hash 'shouldnotexistanywhere' not found in SchemaRegistry."
    )
  }

  @Test
  fun getSchemaTypeId_newSchema() = runBlockingTest {
    val schema = newSchema("abc")

    assertThat(database.getSchemaTypeId(schema, db)).isEqualTo(FIRST_ENTITY_TYPE_ID)

    // Repeating should give the same result.
    assertThat(database.getSchemaTypeId(schema, db)).isEqualTo(FIRST_ENTITY_TYPE_ID)

    assertThat(database.getTypeIdForTest(FieldType.EntityRef("abc")))
      .isEqualTo(FIRST_ENTITY_TYPE_ID)
  }

  @Test
  fun getSchemaTypeId_multipleNewSchemas() = runBlockingTest {
    val schema1 = newSchema("first")
    val schema2 = newSchema("second")
    val expectedTypeId1 = FIRST_ENTITY_TYPE_ID
    val expectedTypeId2 = FIRST_ENTITY_TYPE_ID + 1

    assertThat(database.getSchemaTypeId(schema1, db)).isEqualTo(expectedTypeId1)
    assertThat(database.getTypeIdForTest(FieldType.EntityRef("first")))
      .isEqualTo(expectedTypeId1)

    assertThat(database.getSchemaTypeId(schema2, db)).isEqualTo(expectedTypeId2)
    assertThat(database.getTypeIdForTest(FieldType.EntityRef("second")))
      .isEqualTo(expectedTypeId2)
  }

  @Test
  fun getSchemaTypeId_withPrimitiveFields() = runBlockingTest {
    val schema = newSchema(
      "abc",
      SchemaFields(
        singletons = mapOf("text" to FieldType.Text, "bool" to FieldType.Boolean),
        collections = mapOf("num" to FieldType.Number)
      )
    )

    val typeId = database.getSchemaTypeId(schema, db)

    assertThat(typeId).isEqualTo(FIRST_ENTITY_TYPE_ID)
    assertThat(readFieldsTable()).containsExactly(
      FieldRow(1, TEXT_TYPE_ID, typeId, "text"),
      FieldRow(2, BOOLEAN_TYPE_ID, typeId, "bool"),
      FieldRow(3, NUMBER_TYPE_ID, typeId, "num")
    )
  }

  @Test
  fun getSchemaFields() = runBlockingTest {
    val schema1 = newSchema(
      "abc",
      SchemaFields(
        singletons = mapOf("text" to FieldType.Text, "bool" to FieldType.Boolean),
        collections = mapOf("num" to FieldType.Number)
      )
    )
    val schemaTypeId1 = database.getSchemaTypeId(schema1, db)

    // Creates new IDs for each field.
    val fields1 = database.getSchemaFields(schemaTypeId1, db)
    assertThat(fields1).containsExactly(
      "text", DatabaseImpl.SchemaField("text", 1L, TEXT_TYPE_ID, FieldClass.Singleton),
      "bool", DatabaseImpl.SchemaField("bool", 2L, BOOLEAN_TYPE_ID, FieldClass.Singleton),
      "num", DatabaseImpl.SchemaField("num", 3L, NUMBER_TYPE_ID, FieldClass.Collection)
    )

    // Re-running with the same schema doesn't create new field IDs
    assertThat(database.getSchemaFields(schemaTypeId1, db)).isEqualTo(fields1)

    // Running on a different schema creates new field IDs.
    val schema2 = schema1.copy(hash = "xyz")
    val schemaTypeId2 = database.getSchemaTypeId(schema2, db)
    val fields2 = database.getSchemaFields(schemaTypeId2, db)
    assertThat(fields2).containsExactly(
      "text", DatabaseImpl.SchemaField("text", 4L, TEXT_TYPE_ID, FieldClass.Singleton),
      "bool", DatabaseImpl.SchemaField("bool", 5L, BOOLEAN_TYPE_ID, FieldClass.Singleton),
      "num", DatabaseImpl.SchemaField("num", 6L, NUMBER_TYPE_ID, FieldClass.Collection)
    )
  }

  @Test
  fun getSchemaFields_emptySchema() = runBlockingTest {
    val schema = newSchema("abc")
    val schemaTypeId = database.getSchemaTypeId(schema, db)
    assertThat(database.getSchemaFields(schemaTypeId, db)).isEmpty()
  }

  @Test
  fun getSchemaFields_unknownSchemaId() = runBlockingTest {
    val fieldIds = database.getSchemaFields(987654L, db)
    assertThat(fieldIds).isEmpty()
  }

  @Test
  fun createEntityStorageKeyId_createsNewIds() = runBlockingTest {
    assertThat(
      createEntityStorageKeyId(DummyStorageKey("key1"), "eid1")
    ).isEqualTo(1L)

    assertThat(
      createEntityStorageKeyId(DummyStorageKey("key2"), "eid2")
    ).isEqualTo(2L)

    assertThat(
      createEntityStorageKeyId(DummyStorageKey("key3"), "eid3")
    ).isEqualTo(3L)
  }

  @Test
  fun createEntityStorageKeyId_replacesExistingIds() = runBlockingTest {
    // Insert keys for the first time.

    assertThat(
      createEntityStorageKeyId(DummyStorageKey("key1"), "eid1", databaseVersion = 1)
    ).isEqualTo(1L)

    assertThat(
      createEntityStorageKeyId(DummyStorageKey("key2"), "eid2", databaseVersion = 1)
    ).isEqualTo(2L)

    // Inserting again should overwrite them.

    assertThat(
      createEntityStorageKeyId(DummyStorageKey("key1"), "eid1", databaseVersion = 2)
    ).isEqualTo(3L)

    assertThat(
      createEntityStorageKeyId(DummyStorageKey("key2"), "eid2", databaseVersion = 2)
    ).isEqualTo(4L)
  }

  @Test
  fun createEntityStorageKeyId_wrongEntityId() = runBlockingTest {
    val key = DummyStorageKey("key")
    createEntityStorageKeyId(key, "correct-entity-id")

    val exception = assertFailsWith<IllegalArgumentException> {
      createEntityStorageKeyId(key, "incorrect-entity-id")
    }
    assertThat(exception).hasMessageThat().isEqualTo(
      "Expected storage key dummy://key to have entity ID incorrect-entity-id but was " +
        "correct-entity-id."
    )
  }

  @Test
  fun createEntityStorageKeyId_versionNumberMustBeOneLarger() = runBlockingTest {
    val key = DummyStorageKey("key")
    val entityId = "entity-id"
    val originalStorageKeyId = createEntityStorageKeyId(
      key,
      entityId,
      databaseVersion = 10
    )
    assertThat(originalStorageKeyId).isNotNull()

    // Same version number is rejected.
    assertThat(
      createEntityStorageKeyId(key, entityId, databaseVersion = 10)
    ).isNull()

    // Smaller version number is rejected.
    assertThat(
      createEntityStorageKeyId(key, entityId, databaseVersion = 9)
    ).isNull()

    // Increasing version number by more than 1 is rejected.
    assertThat(
      createEntityStorageKeyId(key, entityId, databaseVersion = 12)
    ).isNull()

    // Increasing version number by 1 is ok.
    val newStorageKeyId = createEntityStorageKeyId(key, entityId, databaseVersion = 11)
    assertThat(newStorageKeyId).isNotNull()
    // TODO: If the storage key is the same, there's no need to delete the old one and create a
    // new one.
    assertThat(newStorageKeyId).isNotEqualTo(originalStorageKeyId)
  }

  @Test
  fun insertAndGet_entity_versionNotOneLarger_returnsFalse() = runBlockingTest {
    val entityV1 = EMPTY_ENTITY.copy(databaseVersion = 1)
    val entityV2 = EMPTY_ENTITY.copy(databaseVersion = 2)
    val entityV3 = EMPTY_ENTITY.copy(databaseVersion = 3)

    // First version is accepted.
    assertThat(database.insertOrUpdateEntity(STORAGE_KEY, entityV1)).isTrue()
    // Version too high, rejected.
    assertThat(database.insertOrUpdateEntity(STORAGE_KEY, entityV3)).isFalse()
    // Version too low, rejected.
    assertThat(database.insertOrUpdateEntity(STORAGE_KEY, entityV1)).isFalse()
    // Correct next version, accepted.
    assertThat(database.insertOrUpdateEntity(STORAGE_KEY, entityV2)).isTrue()
  }

  @Test
  fun insertAndGet_entity_newEmptyEntity() = runBlockingTest {
    database.insertOrUpdateEntity(STORAGE_KEY, EMPTY_ENTITY)
    val entityOut = database.getEntity(STORAGE_KEY, EMPTY_SCHEMA)

    assertThat(entityOut).isEqualTo(EMPTY_ENTITY)
  }

  @Test
  fun insertAndGet_entity_newFixtureEntity() = runBlockingTest {
    FixtureEntities.registerSchemas()
    val entity = fixtureEntities.generate().toDatabaseData()

    database.insertOrUpdateEntity(STORAGE_KEY, entity)
    val entityOut = database.getEntity(STORAGE_KEY, FixtureEntity.SCHEMA)

    assertThat(entityOut).isEqualTo(entity)
  }

  @Test
  fun insertAndGet_entity_newEntityWithEmptyLists() = runBlockingTest {
    val key = DummyStorageKey("key")

    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf(
          "textlist" to FieldType.ListOf(FieldType.Text),
          "longlist" to FieldType.ListOf(FieldType.Long),
          "nulltextlist" to FieldType.ListOf(FieldType.Text),
          "nulllonglist" to FieldType.ListOf(FieldType.Long)
        ),
        collections = emptyMap()
      )
    )

    val entity = DatabaseData.Entity(
      RawEntity(
        "entity",
        mapOf(
          "textlist" to
            emptyList<ReferencablePrimitive<String>>()
              .toReferencable(FieldType.ListOf(FieldType.Text)),
          "longlist" to
            emptyList<ReferencablePrimitive<Long>>()
              .toReferencable(FieldType.ListOf(FieldType.Long)),
          "nulltextlist" to null,
          "nulllonglist" to null
        ),
        emptyMap()
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    database.insertOrUpdateEntity(key, entity)
    val entityOut = database.getEntity(key, schema)
    assertThat(entityOut).isEqualTo(entity)
  }

  @Test
  fun insertAndGet_entity_withCreationAndExpiration() = runBlockingTest {
    val key = DummyStorageKey("key")
    val schema = newSchema(
      "hash",
      SchemaFields(singletons = mapOf("text" to FieldType.Text), collections = mapOf())
    )
    val entity = DatabaseData.Entity(
      RawEntity("entity", mapOf("text" to "abc".toReferencable()), mapOf(), 11L, 111L),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    database.insertOrUpdateEntity(key, entity)
    val entityOut = database.getEntity(key, schema)
    assertThat(entityOut).isEqualTo(entity)
  }

  @Test
  fun insertAndGet_entity_newEntityWithReferenceFields() = runBlockingTest {
    val key = DummyStorageKey("key")
    val childSchema = newSchema(
      "child",
      SchemaFields(
        singletons = mapOf("name" to FieldType.Text),
        collections = mapOf()
      )
    )
    val schema = newSchema(
      "parent",
      SchemaFields(
        singletons = mapOf("favouriteChild" to FieldType.EntityRef("child")),
        collections = mapOf("otherChildren" to FieldType.EntityRef("child"))
      )
    )
    val alice = DatabaseData.Entity(
      RawEntity("alice-id", singletons = mapOf("name" to "Alice".toReferencable())),
      childSchema,
      1,
      VersionMap("alice" to 1)
    )
    val bob = DatabaseData.Entity(
      RawEntity("bob-id", singletons = mapOf("name" to "Bob".toReferencable())),
      childSchema,
      1,
      VersionMap("bob" to 2)
    )
    val charlie = DatabaseData.Entity(
      RawEntity("charlie-id", singletons = mapOf("name" to "Charlie".toReferencable())),
      childSchema,
      1,
      VersionMap("charlie" to 3)
    )
    val parentEntity = DatabaseData.Entity(
      RawEntity(
        "parent-id",
        mapOf(
          "favouriteChild" to RawReference(
            "alice-id",
            DummyStorageKey("alice-key"),
            VersionMap("alice" to 1)
          )
        ),
        mapOf(
          "otherChildren" to setOf(
            RawReference("bob-id", DummyStorageKey("bob-key"), VersionMap("bob" to 2)),
            RawReference(
              "charlie-id",
              DummyStorageKey("charlie-key"),
              VersionMap("charlie" to 3)
            )
          )
        )
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    database.insertOrUpdateEntity(DummyStorageKey("alice-key"), alice)
    database.insertOrUpdateEntity(DummyStorageKey("bob-key"), bob)
    database.insertOrUpdateEntity(DummyStorageKey("charlie-key"), charlie)

    database.insertOrUpdateEntity(key, parentEntity)
    val entityOut = database.getEntity(key, schema)

    assertThat(entityOut).isEqualTo(parentEntity)
  }

  @Test
  fun insertAndGet_entity_withHardReferenceField() = runBlockingTest {
    val key = DummyStorageKey("key")
    val schema = newSchema(
      "parent",
      SchemaFields(
        singletons = mapOf("child" to FieldType.EntityRef("child")),
        collections = emptyMap()
      )
    )
    SchemaRegistry.register(Schema.EMPTY.copy(hash = "child"))

    val parentEntity = DatabaseData.Entity(
      RawEntity(
        "parent-id",
        mapOf(
          "child" to RawReference(
            "child-id",
            DummyStorageKey("child-key"),
            VersionMap("child" to 1),
            isHardReference = true
          )
        ),
        emptyMap()
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    database.insertOrUpdateEntity(key, parentEntity)
    val entityOut = database.getEntity(key, schema)

    assertThat(entityOut).isEqualTo(parentEntity)
    val reference = entityOut!!.rawEntity.singletons["child"] as RawReference
    assertThat(reference.isHardReference).isTrue()
  }

  @Test
  fun insertAndGet_entity_updateExistingEntity() = runBlockingTest {
    val key = DummyStorageKey("key")
    val childSchema = newSchema("child")
    database.getSchemaTypeId(childSchema, db)
    newSchema(
      "inlineInlineHash",
      SchemaFields(
        singletons = mapOf(
          "text" to FieldType.Text
        ),
        collections = emptyMap()
      )
    )
    newSchema(
      "inlineHash",
      SchemaFields(
        singletons = mapOf(
          "text" to FieldType.Text,
          "num" to FieldType.Number,
          "inline" to FieldType.InlineEntity("inlineInlineHash")
        ),
        collections = emptyMap()
      )
    )
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf(
          "text" to FieldType.Text,
          "bool" to FieldType.Boolean,
          "num" to FieldType.Number,
          "ref" to FieldType.EntityRef("child"),
          "inline" to FieldType.InlineEntity("inlineHash"),
          "inlinelist" to FieldType.ListOf(FieldType.InlineEntity("inlineHash"))
        ),
        collections = mapOf(
          "texts" to FieldType.Text,
          "bools" to FieldType.Boolean,
          "nums" to FieldType.Number,
          "refs" to FieldType.EntityRef("child"),
          "inlines" to FieldType.InlineEntity("inlineHash")
        )
      )
    )

    val inlineInlineEntity = RawEntity(
      "",
      singletons = mapOf("text" to "iinnlliinnee".toReferencable())
    )

    fun toInlineEntity(text: String, num: Double) = RawEntity(
      "",
      singletons = mapOf(
        "text" to text.toReferencable(),
        "num" to num.toReferencable(),
        "inline" to inlineInlineEntity
      )
    )

    val inlineEntity = toInlineEntity("qqq", 555.0)
    val inline1 = toInlineEntity("rrr", 666.0)
    val inline2 = toInlineEntity("sss", 777.0)
    val inline3 = toInlineEntity("ttt", 888.0)

    val entityId = "entity"
    val entity1 = DatabaseData.Entity(
      RawEntity(
        entityId,
        mapOf(
          "text" to "aaa".toReferencable(),
          "bool" to true.toReferencable(),
          "num" to 111.0.toReferencable(),
          "ref" to RawReference(
            "child-id-1",
            DummyStorageKey("child-ref-1"),
            VersionMap("child-1" to 1)
          ),
          "inline" to inlineEntity,
          "inlinelist" to listOf(
            toInlineEntity("list1", 1.0),
            toInlineEntity("list2", 2.0)
          ).toReferencable(FieldType.ListOf(FieldType.InlineEntity("inlineHash")))
        ),
        mapOf(
          "texts" to setOf("aaa".toReferencable(), "bbb".toReferencable()),
          "bools" to setOf(true.toReferencable()),
          "nums" to setOf(11.0.toReferencable(), 111.0.toReferencable()),
          "refs" to setOf(
            RawReference(
              "child-id-2",
              DummyStorageKey("child-ref-2"),
              VersionMap("child-2" to 2)
            ),
            RawReference(
              "child-id-3",
              DummyStorageKey("child-ref-3"),
              VersionMap("child-3" to 3)
            )
          ),
          "inlines" to setOf(inline1, inline2)
        )
      ),
      schema,
      1,
      VersionMap("actor" to 1)
    )
    val inlineEntity2 = RawEntity(
      "",
      singletons = mapOf(
        "text" to "rrr".toReferencable(),
        "num" to 666.0.toReferencable()
      )
    )
    val entity2 = DatabaseData.Entity(
      RawEntity(
        entityId,
        mapOf(
          "text" to "zzz".toReferencable(),
          "bool" to false.toReferencable(),
          "num" to 999.0.toReferencable(),
          "ref" to RawReference(
            "child-id-9",
            DummyStorageKey("child-ref-9"),
            VersionMap("child-9" to 9)
          ),
          "inline" to inlineEntity2,
          "inlinelist" to listOf(
            toInlineEntity("list1", 1.0),
            toInlineEntity("list1", 1.0)
          ).toReferencable(FieldType.ListOf(FieldType.InlineEntity("inlineHash")))
        ),
        mapOf(
          "texts" to setOf("zzz".toReferencable(), "yyy".toReferencable()),
          "bools" to setOf(false.toReferencable()),
          "nums" to setOf(99.0.toReferencable(), 999.0.toReferencable()),
          "refs" to setOf(
            RawReference(
              "child-id-8",
              DummyStorageKey("child-ref-8"),
              VersionMap("child-8" to 8)
            ),
            RawReference(
              "child-id-7",
              DummyStorageKey("child-ref-7"),
              VersionMap("child-7" to 7)
            )
          ),
          "inlines" to setOf(inline2, inline3)
        )
      ),
      schema,
      2,
      VersionMap("actor" to 2)
    )

    val clearedEntity = DatabaseData.Entity(
      RawEntity(
        entityId,
        mapOf(
          "text" to null,
          "bool" to null,
          "num" to null,
          "ref" to null,
          "inline" to null,
          "inlinelist" to null
        ),
        mapOf(
          "texts" to emptySet(),
          "bools" to emptySet(),
          "nums" to emptySet(),
          "refs" to emptySet(),
          "inlines" to emptySet()
        )
      ),
      schema,
      3,
      VersionMap("actor" to 3)
    )

    database.insertOrUpdateEntity(key, entity1)
    database.insertOrUpdateEntity(key, entity2)
    val entityOut = database.getEntity(key, schema)

    assertThat(entityOut).isEqualTo(entity2)
    database.insertOrUpdateEntity(key, clearedEntity)

    assertTableIsEmpty("field_values")

    // the toplevel entity should remain in the entities and storage_keys
    // tables, but all record of child inline entities should be removed.
    assertTableIsSize("entities", 1)
    assertTableIsSize("storage_keys", 1)
  }

  @Test
  fun insertAndGet_entity_singletonField_isMissing() = runBlockingTest {
    val key = DummyStorageKey("key")
    val childSchema = newSchema("child")
    database.getSchemaTypeId(childSchema, db)
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("text" to FieldType.Text),
        collections = mapOf()
      )
    )
    val entity = DatabaseData.Entity(
      RawEntity("entity", mapOf()),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    database.insertOrUpdateEntity(key, entity)
    val entityOut = database.getEntity(key, schema)
    assertThat(entityOut!!.rawEntity.singletons).containsExactly("text", null)
  }

  @Test
  fun insertAndGet_entity_singletonField_isNull() = runBlockingTest {
    val key = DummyStorageKey("key")
    val childSchema = newSchema("child")
    database.getSchemaTypeId(childSchema, db)
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("text" to FieldType.Text),
        collections = mapOf()
      )
    )
    val entity = DatabaseData.Entity(
      RawEntity("entity", mapOf("text" to null)),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    database.insertOrUpdateEntity(key, entity)
    val entityOut = database.getEntity(key, schema)
    assertThat(entityOut).isEqualTo(entity)
  }

  @Test
  fun insertAndGet_entity_collectionFields_areMissing() = runBlockingTest {
    val key = DummyStorageKey("key")
    val childSchema = newSchema("child")
    database.getSchemaTypeId(childSchema, db)
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf(),
        collections = mapOf(
          "texts" to FieldType.Text,
          "refs" to FieldType.EntityRef("child")
        )
      )
    )
    val entity = DatabaseData.Entity(
      EMPTY_RAW_ENTITY,
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    database.insertOrUpdateEntity(key, entity)
    val entityOut = database.getEntity(key, schema)
    assertThat(entityOut!!.rawEntity.collections).containsExactly(
      "texts", emptySet<Referencable>(),
      "refs", emptySet<Referencable>()
    )
  }

  @Test
  fun insert_entity_singleReferenceField_wrongType() = runBlockingTest {
    val childSchema = newSchema("child")
    database.getSchemaTypeId(childSchema, db)
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("ref" to FieldType.EntityRef("child")),
        collections = emptyMap()
      )
    )

    val exception = assertFailsWith<IllegalArgumentException> {
      database.insertOrUpdateEntity(
        DummyStorageKey("key"),
        DatabaseData.Entity(
          RawEntity(
            "entity",
            // Should be a Reference.
            singletons = mapOf("ref" to "abc".toReferencable())
          ),
          schema,
          FIRST_VERSION_NUMBER,
          VERSION_MAP
        )
      )
    }
    assertThat(exception).hasMessageThat().isEqualTo(
      "Expected field value to be a Reference but was Primitive(abc)."
    )
  }

  @Test
  fun insert_entity_collectionReferenceField_wrongType() = runBlockingTest {
    val childSchema = newSchema("child")
    database.getSchemaTypeId(childSchema, db)
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = emptyMap(),
        collections = mapOf("refs" to FieldType.EntityRef("child"))
      )
    )

    val exception = assertFailsWith<IllegalArgumentException> {
      database.insertOrUpdateEntity(
        DummyStorageKey("key"),
        DatabaseData.Entity(
          RawEntity(
            "entity",
            // Should be Set<Reference>.
            collections = mapOf("refs" to setOf("abc".toReferencable()))
          ),
          schema,
          FIRST_VERSION_NUMBER,
          VERSION_MAP
        )
      )
    }
    assertThat(exception).hasMessageThat().isEqualTo(
      "Expected element in collection to be a Reference but was Primitive(abc)."
    )
  }

  @Test
  fun get_entity_unknownStorageKey() = runBlockingTest {
    assertThat(database.getEntity(DummyStorageKey("nope"), newSchema("hash"))).isNull()
  }

  @Test
  fun applyOp_flagDisabled_throwsException() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val reference = RawReference("ref1", DummyStorageKey("backing"), VersionMap())

    BuildFlags.WRITE_ONLY_STORAGE_STACK = false

    assertFailsWith<BuildFlagDisabledError> {
      database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference, SINGLE_FIELD_SCHEMA))
    }
  }

  @Test
  fun applyOp_insertOneEntity() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val reference = RawReference("ref1", DummyStorageKey("backing"), VersionMap())

    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference, SINGLE_FIELD_SCHEMA))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(ReferenceWithVersion(reference, VersionMap(DATABASE_CRDT_ACTOR to 1))),
      schema = SINGLE_FIELD_SCHEMA,
      databaseVersion = 1,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 1)
    )
    assertThat(database.getCollection(collectionKey, SINGLE_FIELD_SCHEMA)).isEqualTo(
      expectedCollection
    )
  }

  @Test
  fun applyOp_insertTwoEntities() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    val reference2 = RawReference("ref2", backingKey, VersionMap())

    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, SINGLE_FIELD_SCHEMA))
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference2, SINGLE_FIELD_SCHEMA))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(reference1, VersionMap(DATABASE_CRDT_ACTOR to 1)),
        ReferenceWithVersion(reference2, VersionMap(DATABASE_CRDT_ACTOR to 2))
      ),
      schema = SINGLE_FIELD_SCHEMA,
      databaseVersion = 2,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 2)
    )
    assertThat(database.getCollection(collectionKey, SINGLE_FIELD_SCHEMA)).isEqualTo(
      expectedCollection
    )
  }

  @Test
  fun applyOp_insertSameEntityTwice() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val reference = RawReference("ref1", DummyStorageKey("backing"), VersionMap())

    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference, SINGLE_FIELD_SCHEMA))
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference, SINGLE_FIELD_SCHEMA))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(ReferenceWithVersion(reference, VersionMap(DATABASE_CRDT_ACTOR to 1))),
      schema = SINGLE_FIELD_SCHEMA,
      databaseVersion = 1,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 1)
    )
    assertThat(database.getCollection(collectionKey, SINGLE_FIELD_SCHEMA)).isEqualTo(
      expectedCollection
    )
  }

  @Test
  fun applyOp_notifies() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val reference = RawReference("ref1", DummyStorageKey("backing"), VersionMap())
    val client = FakeDatabaseClient(collectionKey).also { database.addClient(it) }
    val clientId = 22

    database.applyOp(
      collectionKey,
      DatabaseOp.AddToCollection(reference, SINGLE_FIELD_SCHEMA),
      clientId
    )

    client.eventMutex.withLock {
      assertThat(client.updates).hasSize(1)
      assertThat(client.updates.single().originatingClientId).isEqualTo(clientId)
    }
  }

  @Test
  fun applyOp_addEntity_afterInsertOrUpdate() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    val reference2 = RawReference("ref2", backingKey, VersionMap())
    val startCollection = DatabaseData.Collection(
      values = setOf(ReferenceWithVersion(reference1, VersionMap("a1" to 1))),
      schema = SINGLE_FIELD_SCHEMA,
      databaseVersion = 5,
      versionMap = VersionMap("a2" to 2)
    )

    database.insertOrUpdate(collectionKey, startCollection)
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference2, SINGLE_FIELD_SCHEMA))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(reference1, VersionMap("a1" to 1)),
        ReferenceWithVersion(reference2, VersionMap("a2" to 2, DATABASE_CRDT_ACTOR to 1))
      ),
      schema = SINGLE_FIELD_SCHEMA,
      databaseVersion = 6,
      versionMap = VersionMap("a2" to 2, DATABASE_CRDT_ACTOR to 1)
    )
    assertThat(database.getCollection(collectionKey, SINGLE_FIELD_SCHEMA)).isEqualTo(
      expectedCollection
    )
  }

  @Test
  fun applyOp_addEntity_beforeInsertOrUpdate() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    val reference2 = RawReference("ref2", backingKey, VersionMap())
    val collection = DatabaseData.Collection(
      values = setOf(ReferenceWithVersion(reference2, VersionMap("a1" to 1))),
      schema = SINGLE_FIELD_SCHEMA,
      databaseVersion = 2,
      versionMap = VersionMap("a2" to 2)
    )

    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, SINGLE_FIELD_SCHEMA))
    database.insertOrUpdate(collectionKey, collection)

    assertThat(database.getCollection(collectionKey, SINGLE_FIELD_SCHEMA)).isEqualTo(
      collection
    )
  }

  @Test
  fun applyOp_clearCollection() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    val reference2 = RawReference("ref2", backingKey, VersionMap())
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, schema))
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference2, schema))

    database.applyOp(collectionKey, DatabaseOp.ClearCollection(schema))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(),
      schema = schema,
      databaseVersion = 2,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 2)
    )
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(expectedCollection)
  }

  @Test
  fun applyOp_clearCollection_alreadyEmpty() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val emptyCollection = dbCollection(backingKey, schema)
    database.insertOrUpdate(collectionKey, emptyCollection)

    database.applyOp(collectionKey, DatabaseOp.ClearCollection(schema))

    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(emptyCollection)
  }

  @Test
  fun applyOp_clearCollection_collectionDoesNotExist() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")

    database.applyOp(collectionKey, DatabaseOp.ClearCollection(schema))

    assertThat(database.getCollection(collectionKey, schema)).isNull()
  }

  @Test
  fun applyOp_clearCollection_doesNotChangeOtherCollections() = runBlockingTest {
    val schema = newSchema("hash")
    val collection1Key = DummyStorageKey("collection1")
    val collection2Key = DummyStorageKey("collection2")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    val reference2 = RawReference("ref2", backingKey, VersionMap())
    database.applyOp(collection1Key, DatabaseOp.AddToCollection(reference1, schema))
    database.applyOp(collection2Key, DatabaseOp.AddToCollection(reference2, schema))

    database.applyOp(collection1Key, DatabaseOp.ClearCollection(schema))

    val expectedCollection1 = DatabaseData.Collection(
      values = setOf(),
      schema = schema,
      databaseVersion = 1,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 1)
    )
    val expectedCollection2 = DatabaseData.Collection(
      values = setOf(ReferenceWithVersion(reference2, VersionMap(DATABASE_CRDT_ACTOR to 1))),
      schema = schema,
      databaseVersion = 1,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 1)
    )
    assertThat(database.getCollection(collection1Key, schema)).isEqualTo(expectedCollection1)
    assertThat(database.getCollection(collection2Key, schema)).isEqualTo(expectedCollection2)
  }

  @Test
  fun applyOp_clearCollection_afterInsertOrUpdate() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    val startCollection = DatabaseData.Collection(
      values = setOf(ReferenceWithVersion(reference1, VersionMap("a1" to 1))),
      schema = schema,
      databaseVersion = 5,
      versionMap = VersionMap("a2" to 2)
    )

    database.insertOrUpdate(collectionKey, startCollection)
    database.applyOp(collectionKey, DatabaseOp.ClearCollection(schema))

    val expectedCollection = DatabaseData.Collection(
      values = emptySet(),
      schema = schema,
      databaseVersion = 5,
      versionMap = VersionMap("a2" to 2)
    )
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(
      expectedCollection
    )
  }

  @Test
  fun applyOp_clearCollection_alsoClearsEntities() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(singletons = mapOf("text" to FieldType.Text), collections = mapOf())
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val entity1Key = DummyStorageKey("backing/entity1")
    val entity2Key = DummyStorageKey("backing/entity2")
    val reference1 = RawReference("entity1", backingKey, VersionMap())
    val reference2 = RawReference("entity2", backingKey, VersionMap())
    val entity1 = RawEntity(
      "entity1",
      mapOf("text" to "abc".toReferencable()),
      mapOf()
    ).toDatabaseData(schema)
    val entity2 = RawEntity(
      "entity2",
      mapOf("text" to "ddd".toReferencable()),
      mapOf()
    ).toDatabaseData(schema)
    database.insertOrUpdate(entity1Key, entity1)
    database.insertOrUpdate(entity2Key, entity2)
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, schema))
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference2, schema))

    database.applyOp(collectionKey, DatabaseOp.ClearCollection(schema))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(),
      schema = schema,
      databaseVersion = 3,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 2)
    )
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(expectedCollection)
    assertThat(database.getEntity(entity1Key, schema)).isEqualTo(entity1.nulled())
    assertThat(database.getEntity(entity2Key, schema)).isEqualTo(entity2.nulled())
  }

  @Test
  fun applyOp_removeFromCollection() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    val reference2 = RawReference("ref2", backingKey, VersionMap())
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, schema))
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference2, schema))

    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("ref1", schema))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(reference2, VersionMap(DATABASE_CRDT_ACTOR to 2))
      ),
      schema = schema,
      databaseVersion = 2,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 2)
    )
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(expectedCollection)
  }

  @Test
  fun applyOp_removeFromCollection_onlyElement() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, schema))

    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("ref1", schema))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(),
      schema = schema,
      databaseVersion = 1,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 1)
    )
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(expectedCollection)
  }

  @Test
  fun applyOp_removeFromCollection_twiceSameElement() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, schema))

    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("ref1", schema))
    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("ref1", schema))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(),
      schema = schema,
      databaseVersion = 1,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 1)
    )
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(expectedCollection)
  }

  @Test
  fun applyOp_removeFromCollection_elementNotContained() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, schema))

    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("different-ref", schema))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(reference1, VersionMap(DATABASE_CRDT_ACTOR to 1))
      ),
      schema = schema,
      databaseVersion = 1,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 1)
    )
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(expectedCollection)
  }

  @Test
  fun applyOp_removeFromCollection_emptyCollection() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val emptyCollection = dbCollection(backingKey, schema)
    database.insertOrUpdate(collectionKey, emptyCollection)

    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("ref1", schema))

    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(emptyCollection)
  }

  @Test
  fun applyOp_removeFromCollection_collectionDoesNotExist() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")

    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("ref1", schema))

    assertThat(database.getCollection(collectionKey, schema)).isNull()
  }

  @Test
  fun applyOp_removeFromCollection_afterInsertOrUpdate() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val reference1 = RawReference("ref1", backingKey, VersionMap())
    val reference2 = RawReference("ref2", backingKey, VersionMap())
    val startCollection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(reference1, VersionMap("a1" to 1)),
        ReferenceWithVersion(reference2, VersionMap("a1" to 2))
      ),
      schema = schema,
      databaseVersion = 5,
      versionMap = VersionMap("a2" to 2)
    )

    database.insertOrUpdate(collectionKey, startCollection)
    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("ref1", schema))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(ReferenceWithVersion(reference2, VersionMap("a1" to 2))),
      schema = schema,
      databaseVersion = 5,
      versionMap = VersionMap("a2" to 2)
    )
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(
      expectedCollection
    )
  }

  @Test
  fun applyOp_removeFromCollection_alsoClearsEntities() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(singletons = mapOf("text" to FieldType.Text), collections = mapOf())
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val entity1Key = DummyStorageKey("backing/entity1")
    val entity2Key = DummyStorageKey("backing/entity2")
    val reference1 = RawReference("entity1", backingKey, VersionMap())
    val reference2 = RawReference("entity2", backingKey, VersionMap())
    val entity1 = RawEntity(
      "entity1",
      mapOf("text" to "abc".toReferencable()),
      mapOf()
    ).toDatabaseData(schema)
    val entity2 = RawEntity(
      "entity2",
      mapOf("text" to "ddd".toReferencable()),
      mapOf()
    ).toDatabaseData(schema)
    database.insertOrUpdate(entity1Key, entity1)
    database.insertOrUpdate(entity2Key, entity2)
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, schema))
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference2, schema))

    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("entity1", schema))

    val expectedCollection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(reference2, VersionMap(DATABASE_CRDT_ACTOR to 2))
      ),
      schema = schema,
      databaseVersion = 3,
      versionMap = VersionMap(DATABASE_CRDT_ACTOR to 2)
    )
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(expectedCollection)
    assertThat(database.getEntity(entity1Key, schema)).isEqualTo(entity1.nulled())
    assertThat(database.getEntity(entity2Key, schema)).isEqualTo(entity2)
  }

  @Test
  fun applyOp_removeFromCollection_notifiesWhenClears() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(singletons = mapOf("text" to FieldType.Text), collections = mapOf())
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val entity1Key = DummyStorageKey("backing/entity1")
    val reference1 = RawReference("entity1", backingKey, VersionMap())
    val entity1 = RawEntity(
      "entity1",
      mapOf("text" to "abc".toReferencable()),
      mapOf()
    ).toDatabaseData(schema)
    database.insertOrUpdate(entity1Key, entity1)
    database.applyOp(collectionKey, DatabaseOp.AddToCollection(reference1, schema))
    // Add clients to verify updates.
    val collectionClient = FakeDatabaseClient(collectionKey).also { database.addClient(it) }
    val entityClient = FakeDatabaseClient(entity1Key).also { database.addClient(it) }
    val clientId = 22

    database.applyOp(collectionKey, DatabaseOp.RemoveFromCollection("entity1", schema), clientId)

    collectionClient.eventMutex.withLock {
      assertThat(collectionClient.updates).hasSize(1)
      assertThat(collectionClient.updates.single().originatingClientId).isEqualTo(clientId)
    }
    entityClient.eventMutex.withLock {
      assertThat(entityClient.deletes).containsExactly(clientId)
    }
  }

  @Test
  fun applyOp_clearCollection_collectionDidntExist_doesNotNotify() = runBlockingTest {
    val schema = newSchema("hash")
    val collectionKey = DummyStorageKey("collection")
    // Add a client.
    val collectionClient = FakeDatabaseClient(collectionKey).also { database.addClient(it) }

    database.applyOp(collectionKey, DatabaseOp.ClearCollection(schema))

    assertThat(database.getCollection(collectionKey, schema)).isNull()
    collectionClient.eventMutex.withLock {
      assertThat(collectionClient.updates).isEmpty()
    }
  }

  @Test
  fun insertAndGet_collection_newEmptyCollection() = runBlockingTest {
    val key = DummyStorageKey("key")
    val schema = newSchema("hash")
    val inputCollection = DatabaseData.Collection(
      values = emptySet(),
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )

    database.insertOrUpdate(key, inputCollection)
    val outputCollection = database.getCollection(key, schema)

    assertThat(outputCollection).isEqualTo(inputCollection)
  }

  @Test
  fun insertAndGet_collection_newCollectionOfEntities() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val schema = newSchema("hash")
    val inputCollection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(
          RawReference("ref1", backingKey, VersionMap("ref1" to 1)),
          VersionMap("actor" to 1)
        ),
        ReferenceWithVersion(
          RawReference("ref2", backingKey, VersionMap("ref2" to 2)),
          VersionMap("actor" to 2)
        )
      ),
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )

    database.insertOrUpdate(collectionKey, inputCollection)
    val outputCollection = database.getCollection(collectionKey, schema)

    assertThat(outputCollection).isEqualTo(inputCollection)
  }

  @Test
  fun insertAndGet_collection_canChangeElements() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val schema = newSchema("hash")
    val values = mutableSetOf(
      ReferenceWithVersion(
        RawReference("ref", backingKey, VersionMap("ref" to 1)),
        VersionMap("actor" to 1)
      ),
      ReferenceWithVersion(
        RawReference("ref-to-remove", backingKey, VersionMap("ref-to-remove" to 2)),
        VersionMap("actor" to 2)
      )
    )
    val inputCollection1 = DatabaseData.Collection(
      values = values,
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdate(collectionKey, inputCollection1)

    // Test removal of old elements.
    values.removeIf { it.rawReference.id == "ref-to-remove" }
    val inputCollection2 = inputCollection1.copy(values = values, databaseVersion = 2)
    database.insertOrUpdate(collectionKey, inputCollection2)
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(inputCollection2)

    // Test addition of new elements.
    values.add(
      ReferenceWithVersion(
        RawReference("new-ref", backingKey, VersionMap("new-ref" to 3)),
        VersionMap("actor" to 3)
      )
    )
    val inputCollection3 = inputCollection2.copy(values = values, databaseVersion = 3)
    database.insertOrUpdate(collectionKey, inputCollection3)
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(inputCollection3)

    // Test clearing all elements.
    values.clear()
    val inputCollection4 = inputCollection3.copy(values = values, databaseVersion = 4)
    database.insertOrUpdate(collectionKey, inputCollection4)
    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(inputCollection4)
  }

  @Test
  fun insertAndGet_collection_mustIncrementVersion() = runBlockingTest {
    val key = DummyStorageKey("collection")
    val collection = DatabaseData.Collection(
      values = mutableSetOf(
        ReferenceWithVersion(
          RawReference("ref", DummyStorageKey("backing"), VersionMap("ref" to 1)),
          VersionMap("actor" to 1)
        )
      ),
      schema = newSchema("hash"),
      databaseVersion = 2,
      versionMap = VERSION_MAP
    )
    assertThat(database.insertOrUpdate(key, collection)).isTrue()

    assertThat(database.insertOrUpdate(key, collection.copy(databaseVersion = 1)))
      .isFalse()
  }

  @Test
  fun get_collection_unknownStorageKey() = runBlockingTest {
    assertThat(database.getCollection(DummyStorageKey("key"), newSchema("hash"))).isNull()
  }

  @Test
  fun insertAndGet_singleton_newWithNullRef() = runBlockingTest {
    val key = DummyStorageKey("key")
    val schema = newSchema("hash")
    val inputSingleton = DatabaseData.Singleton(
      value = null,
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )

    database.insertOrUpdateSingleton(key, inputSingleton)
    val outputSingleton = database.getSingleton(key, schema)

    assertThat(outputSingleton).isEqualTo(inputSingleton)
  }

  @Test
  fun insertAndGet_singleton_newWithRef() = runBlockingTest {
    val singletonKey = DummyStorageKey("singleton")
    val backingKey = DummyStorageKey("backing")
    val schema = newSchema("hash")
    val inputSingleton = DatabaseData.Singleton(
      value = ReferenceWithVersion(
        RawReference("ref", backingKey, VersionMap("ref" to 1)),
        VersionMap("actor" to 1)
      ),
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )

    database.insertOrUpdateSingleton(singletonKey, inputSingleton)
    val outputSingleton = database.getSingleton(singletonKey, schema)

    assertThat(outputSingleton).isEqualTo(inputSingleton)
  }

  @Test
  fun insertAndGet_singleton_canChangeValues() = runBlockingTest {
    val singletonKey = DummyStorageKey("singleton")
    val backingKey = DummyStorageKey("backing")
    val schema = newSchema("hash")
    val inputSingleton1 = DatabaseData.Singleton(
      value = ReferenceWithVersion(
        RawReference("ref", backingKey, VersionMap("ref" to 1)),
        VersionMap("actor" to 1)
      ),
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdateSingleton(singletonKey, inputSingleton1)

    // Test can change timestamps.
    val inputSingleton2 = inputSingleton1.copy(
      value = ReferenceWithVersion(
        RawReference("ref", backingKey, VersionMap("ref" to 1), 1, 2),
        VersionMap("actor" to 1)
      ),
      databaseVersion = 2
    )
    database.insertOrUpdateSingleton(singletonKey, inputSingleton2)
    assertThat(database.getSingleton(singletonKey, schema)).isEqualTo(inputSingleton2)

    // Test can change reference.
    val inputSingleton3 = inputSingleton1.copy(
      value = ReferenceWithVersion(
        RawReference("new-ref", backingKey, VersionMap("new-ref" to 2)),
        VersionMap("actor" to 2)
      ),
      databaseVersion = 3
    )
    database.insertOrUpdateSingleton(singletonKey, inputSingleton3)
    assertThat(database.getSingleton(singletonKey, schema)).isEqualTo(inputSingleton3)

    // Test can clear value.
    val inputSingleton4 = inputSingleton3.copy(value = null, databaseVersion = 4)
    database.insertOrUpdateSingleton(singletonKey, inputSingleton4)
    assertThat(database.getSingleton(singletonKey, schema)).isEqualTo(inputSingleton4)
  }

  @Test
  fun insertAndGet_singleton_mustIncrementVersion() = runBlockingTest {
    val key = DummyStorageKey("singleton")
    val singleton = DatabaseData.Singleton(
      value = ReferenceWithVersion(
        RawReference("ref", DummyStorageKey("backing"), VersionMap("ref" to 1)),
        VersionMap("actor" to 1)
      ),
      schema = newSchema("hash"),
      databaseVersion = 2,
      versionMap = VERSION_MAP
    )
    assertThat(database.insertOrUpdate(key, singleton, originatingClientId = null)).isTrue()

    assertThat(
      database.insertOrUpdate(
        key,
        singleton.copy(databaseVersion = 1),
        originatingClientId = null
      )
    ).isFalse()
  }

  @Test
  fun get_singleton_unknownStorageKey() = runBlockingTest {
    assertThat(database.getSingleton(DummyStorageKey("key"), newSchema("hash"))).isNull()
  }

  @Test
  fun get_mismatchedDataTypes_entity() = runBlockingTest {
    val entityKey = DummyStorageKey("entity")
    val schema = newSchema("hash")
    val entity = DatabaseData.Entity(
      rawEntity = RawEntity("entity", singletons = mapOf(), collections = mapOf()),
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdateEntity(entityKey, entity)

    val exception1 = assertFailsWith<IllegalArgumentException> {
      database.getCollection(entityKey, schema)
    }
    assertThat(exception1).hasMessageThat().isEqualTo(
      "Expected storage key dummy://entity to be a Collection but was a Entity."
    )

    val exception2 = assertFailsWith<IllegalArgumentException> {
      database.getSingleton(entityKey, schema)
    }
    assertThat(exception2).hasMessageThat().isEqualTo(
      "Expected storage key dummy://entity to be a Singleton but was a Entity."
    )
  }

  @Test
  fun get_mismatchedDataTypes_collection() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val schema = newSchema("hash")
    val collection = DatabaseData.Collection(
      values = emptySet(),
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdate(collectionKey, collection)

    val exception1 = assertFailsWith<IllegalArgumentException> {
      database.getSingleton(collectionKey, schema)
    }
    assertThat(exception1).hasMessageThat().isEqualTo(
      "Expected storage key dummy://collection to be a Singleton but was a Collection."
    )

    val exception2 = assertFailsWith<IllegalArgumentException> {
      database.getEntity(collectionKey, schema)
    }
    assertThat(exception2).hasMessageThat().isEqualTo(
      "Expected storage key dummy://collection to be an Entity but was a Collection."
    )
  }

  @Test
  fun get_mismatchedDataTypes_singleton() = runBlockingTest {
    val singletonKey = DummyStorageKey("singleton")
    val schema = newSchema("hash")
    val singleton = DatabaseData.Singleton(
      value = null,
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdateSingleton(singletonKey, singleton)

    val exception1 = assertFailsWith<IllegalArgumentException> {
      database.getCollection(singletonKey, schema)
    }
    assertThat(exception1).hasMessageThat().isEqualTo(
      "Expected storage key dummy://singleton to be a Collection but was a Singleton."
    )

    val exception2 = assertFailsWith<IllegalArgumentException> {
      database.getEntity(singletonKey, schema)
    }
    assertThat(exception2).hasMessageThat().isEqualTo(
      "Expected storage key dummy://singleton to be an Entity but was a Singleton."
    )
  }

  @Test
  fun insertAndGet_roundTrip_double() = runBlockingTest {
    val largeDouble = 12345678901234567890.0
    val storageKey = DummyStorageKey("entity")
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("x" to FieldType.Number),
        collections = emptyMap()
      )
    )
    val entity = DatabaseData.Entity(
      RawEntity(
        "entity",
        singletons = mapOf("x" to largeDouble.toReferencable())
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    database.insertOrUpdateEntity(storageKey, entity)
    val entityOut = database.getEntity(storageKey, schema)

    assertThat(entityOut).isEqualTo(entity)
    val x = entityOut!!.rawEntity.singletons["x"]
    assertThat(x).isInstanceOf(ReferencablePrimitive::class.java)
    assertThat((x as ReferencablePrimitive<*>).value).isEqualTo(largeDouble)
  }

  @Test
  fun removeAllEntities() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("text" to FieldType.Text),
        collections = mapOf("nums" to FieldType.Number)
      )
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val entity1Key = DummyStorageKey("backing/entity1")
    val entity2Key = DummyStorageKey("backing/entity2")

    val entity1 = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("text" to "abc".toReferencable()),
        mapOf("nums" to setOf(123.0.toReferencable(), 456.0.toReferencable())),
        1L,
        12L
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entity2 = DatabaseData.Entity(
      RawEntity(
        "entity2",
        mapOf("text" to "def".toReferencable()),
        mapOf("nums" to setOf(123.0.toReferencable(), 789.0.toReferencable())),
        3L,
        12L
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val collection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(
          RawReference("entity1", backingKey, VersionMap("ref1" to 1)),
          VersionMap("actor" to 1)
        ),
        ReferenceWithVersion(
          RawReference("entity2", backingKey, VersionMap("ref2" to 2)),
          VersionMap("actor" to 2)
        )
      ),
      schema = schema,
      databaseVersion = FIRST_VERSION_NUMBER,
      versionMap = VERSION_MAP
    )

    database.insertOrUpdate(entity1Key, entity1)
    database.insertOrUpdate(entity2Key, entity2)
    database.insertOrUpdate(collectionKey, collection)

    database.removeAllEntities()

    assertThat(database.getEntity(entity1Key, schema))
      .isEqualTo(
        DatabaseData.Entity(
          RawEntity(
            "entity1",
            mapOf("text" to null),
            mapOf("nums" to emptySet()),
            1L,
            12L
          ),
          schema,
          FIRST_VERSION_NUMBER,
          VERSION_MAP
        )
      )
    assertThat(database.getEntity(entity2Key, schema))
      .isEqualTo(
        DatabaseData.Entity(
          RawEntity(
            "entity2",
            mapOf("text" to null),
            mapOf("nums" to emptySet()),
            3L,
            12L
          ),
          schema,
          FIRST_VERSION_NUMBER,
          VERSION_MAP
        )
      )
    assertThat(database.getCollection(collectionKey, schema))
      .isEqualTo(collection.copy(values = setOf(), databaseVersion = 2))
  }

  @Test
  fun removeEntitiesCreatedBetween() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("text" to FieldType.Text),
        collections = mapOf("nums" to FieldType.Number)
      )
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val entity1Key = DummyStorageKey("backing/entity1")
    val entity2Key = DummyStorageKey("backing/entity2")
    val entity3Key = DummyStorageKey("backing/entity3")

    val entity1 = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("text" to "abc".toReferencable()),
        mapOf("nums" to setOf(123.0.toReferencable(), 456.0.toReferencable())),
        1L, // Creation time
        12L
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entity2 = DatabaseData.Entity(
      RawEntity(
        "entity2",
        mapOf("text" to "def".toReferencable()),
        mapOf("nums" to setOf(123.0.toReferencable(), 789.0.toReferencable())),
        3L, // Creation time
        12L
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entity3 = DatabaseData.Entity(
      RawEntity(
        "entity3",
        mapOf("text" to "ghi".toReferencable()),
        mapOf("nums" to setOf(111.0.toReferencable(), 789.0.toReferencable())),
        5L, // Creation time
        12L
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val collection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(
          RawReference("entity1", backingKey, VersionMap("ref1" to 1)),
          VersionMap("actor" to 1)
        ),
        ReferenceWithVersion(
          RawReference("entity2", backingKey, VersionMap("ref2" to 2)),
          VersionMap("actor" to 2)
        ),
        ReferenceWithVersion(
          RawReference("entity3", backingKey, VersionMap("ref3" to 3)),
          VersionMap("actor" to 3)
        )
      ),
      schema = schema,
      databaseVersion = FIRST_VERSION_NUMBER,
      versionMap = VERSION_MAP
    )

    database.insertOrUpdate(entity1Key, entity1)
    database.insertOrUpdate(entity2Key, entity2)
    database.insertOrUpdate(entity3Key, entity3)
    database.insertOrUpdate(collectionKey, collection)

    database.removeEntitiesCreatedBetween(2, 4)

    // Entity2 should be the only one cleared.
    assertThat(database.getEntity(entity2Key, schema))
      .isEqualTo(
        DatabaseData.Entity(
          RawEntity(
            "entity2",
            mapOf("text" to null),
            mapOf("nums" to emptySet()),
            3L,
            12L
          ),
          schema,
          FIRST_VERSION_NUMBER,
          VERSION_MAP
        )
      )
    assertThat(database.getEntity(entity1Key, schema)).isEqualTo(entity1)
    assertThat(database.getEntity(entity3Key, schema)).isEqualTo(entity3)

    val newValues = setOf(
      ReferenceWithVersion(
        RawReference("entity1", backingKey, VersionMap("ref1" to 1)),
        VersionMap("actor" to 1)
      ),
      ReferenceWithVersion(
        RawReference("entity3", backingKey, VersionMap("ref3" to 3)),
        VersionMap("actor" to 3)
      )
    )
    assertThat(database.getCollection(collectionKey, schema))
      .isEqualTo(collection.copy(values = newValues, databaseVersion = 2))
  }

  @Test
  fun garbageCollection_cleans_inlineEntities() = runBlockingTest {
    newSchema(
      "inlineHash",
      SchemaFields(
        singletons = mapOf("text" to FieldType.Text),
        collections = emptyMap()
      )
    )
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("inline" to FieldType.InlineEntity("inlineHash")),
        collections = mapOf("inlines" to FieldType.InlineEntity("inlineHash"))
      )
    )

    val inlineEntity1 = RawEntity(
      "ie1",
      singletons = mapOf("text" to "ie1".toReferencable())
    )

    val inlineEntity2 = RawEntity(
      "ie2",
      singletons = mapOf("text" to "ie2".toReferencable())
    )

    val entity = DatabaseData.Entity(
      RawEntity(
        "entity",
        singletons = mapOf("inline" to inlineEntity1),
        collections = mapOf("inlines" to setOf(inlineEntity2)),
        creationTimestamp = 100
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    val entityKey = DummyStorageKey("backing/entity")
    database.insertOrUpdate(entityKey, entity)

    database.runGarbageCollection()
    database.runGarbageCollection()

    assertTableIsEmpty("entities")
    assertTableIsEmpty("storage_keys")
    assertTableIsEmpty("field_values")
  }

  @Test
  // Regression test for b/170219293.
  fun garbageCollection_cleans_inlineEntities_storageKeySubset() = runBlockingTest {
    newSchema(
      "inlineHash",
      SchemaFields(
        singletons = mapOf("text" to FieldType.Text),
        collections = emptyMap()
      )
    )
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("inline" to FieldType.InlineEntity("inlineHash")),
        collections = mapOf("inlines" to FieldType.InlineEntity("inlineHash"))
      )
    )
    val inlineEntity1 = RawEntity(
      "ie1",
      singletons = mapOf("text" to "ie1".toReferencable())
    )
    val inlineEntity2 = RawEntity(
      "ie2",
      singletons = mapOf("text" to "ie2".toReferencable())
    )
    val entity1 = DatabaseData.Entity(
      RawEntity(
        "entity",
        singletons = mapOf("inline" to inlineEntity1),
        collections = mapOf("inlines" to setOf(inlineEntity2)),
        creationTimestamp = 100
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entity2 = DatabaseData.Entity(
      RawEntity(
        // Note: this ID (entity2) contains the first ID (entity).
        "entity2",
        singletons = mapOf("inline" to inlineEntity1),
        collections = mapOf("inlines" to setOf(inlineEntity2)),
        creationTimestamp = 100
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entityKey = DummyStorageKey("backing/entity")
    val entityKey2 = DummyStorageKey("backing/entity2")
    database.insertOrUpdate(entityKey, entity1)
    database.insertOrUpdate(entityKey2, entity2)
    val collection = dbCollection(DummyStorageKey("backing"), schema, entity2)
    database.insertOrUpdate(DummyStorageKey("collection"), collection)

    // 2 top level entities, 4 inline entities (2 each), 1 collection.
    assertTableIsSize("storage_keys", 7)

    // Entity1 is not in the collection, entity2 is. So entity1 will be garbage-collected.
    database.runGarbageCollection()
    database.runGarbageCollection()

    assertThat(database.getEntity(entityKey2, schema)).isEqualTo(entity2)
    // Only one top level entity with its 2 inline entities, and the collection.
    assertTableIsSize("storage_keys", 4)
  }

  @Test
  fun garbageCollection() = runBlockingTest {
    val schema = newSchema("hash")
    val backingKey = DummyStorageKey("backing")
    var version = 1
    fun entity(id: String, creationDaysAgo: Long) = DatabaseData.Entity(
      RawEntity(
        id,
        singletons = mapOf(),
        collections = mapOf(),
        creationTimestamp = JvmTime.currentTimeMillis -
          ArcsDuration.ofDays(creationDaysAgo).toMillis()
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    suspend fun updateCollection(vararg entities: DatabaseData.Entity) {
      val values = entities.map {
        ReferenceWithVersion(
          RawReference(it.rawEntity.id, backingKey, VersionMap("ref" to 1)),
          VersionMap("actor" to 1)
        )
      }
      val collection = DatabaseData.Collection(
        values = values.toSet(),
        schema = schema,
        databaseVersion = version++,
        versionMap = VERSION_MAP
      )
      database.insertOrUpdate(DummyStorageKey("collection"), collection)
    }

    val entityInCollectionKey = DummyStorageKey("backing/entityInCollection")
    val entityInCollection = entity("entityInCollection", 10)
    val orphanEntityKey = DummyStorageKey("backing/orphan")
    val orphanEntity = entity("orphan", 10)
    val recentEntityKey = DummyStorageKey("backing/recent")
    val recentEntity = entity("recent", 1)
    val lateRefdEntityKey = DummyStorageKey("backing/lateRefd")
    val lateRefdEntity = entity("lateRefd", 10)
    database.insertOrUpdate(entityInCollectionKey, entityInCollection)
    database.insertOrUpdate(orphanEntityKey, orphanEntity)
    database.insertOrUpdate(recentEntityKey, recentEntity)
    database.insertOrUpdate(lateRefdEntityKey, lateRefdEntity)
    updateCollection(entityInCollection)

    database.runGarbageCollection()

    assertThat(database.getEntity(recentEntityKey, schema)).isEqualTo(recentEntity)
    assertThat(database.getEntity(entityInCollectionKey, schema)).isEqualTo(entityInCollection)
    // After first round both orphanEntity and lateRefdEntityKey should be marked as
    // orphan, but not deleted yet.
    assertThat(database.getEntity(orphanEntityKey, schema)).isEqualTo(orphanEntity)
    assertThat(database.getEntity(lateRefdEntityKey, schema)).isEqualTo(lateRefdEntity)
    assertThat(readOrphanField(orphanEntityKey)).isTrue()
    assertThat(readOrphanField(lateRefdEntityKey)).isTrue()
    assertThat(readOrphanField(recentEntityKey)).isFalse()
    assertThat(readOrphanField(entityInCollectionKey)).isFalse()

    // Now add lateRefdEntity to the collection (in between GC runs).
    updateCollection(entityInCollection, lateRefdEntity)

    database.runGarbageCollection()

    assertThat(database.getEntity(recentEntityKey, schema)).isEqualTo(recentEntity)
    assertThat(database.getEntity(entityInCollectionKey, schema)).isEqualTo(entityInCollection)
    assertThat(database.getEntity(lateRefdEntityKey, schema)).isEqualTo(lateRefdEntity)
    // orphanEntity should have been deleted (orphan on two consecutive runs)
    assertThat(database.getEntity(orphanEntityKey, schema)).isEqualTo(null)
    // lateRefdEntity is no longer orphan.
    assertThat(readOrphanField(lateRefdEntityKey)).isFalse()
    assertThat(readOrphanField(recentEntityKey)).isFalse()
    assertThat(readOrphanField(entityInCollectionKey)).isFalse()
  }

  @Test
  fun garbageCollectionEntityWithNestedEntityRemovedFromCollection() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(
        collections = mapOf("refs" to FieldType.EntityRef("hash")),
        singletons = mapOf("text" to FieldType.Text)
      )
    )
    val backingKey = DummyStorageKey("backing")
    val entityKey = DummyStorageKey("backing/entity")
    val nestedKey = DummyStorageKey("backing/nested")
    var version = 1
    val nested = DatabaseData.Entity(
      RawEntity(
        "nested",
        singletons = mapOf("text" to "abc".toReferencable()),
        collections = mapOf("refs" to setOf()),
        creationTimestamp = JvmTime.currentTimeMillis - ArcsDuration.ofDays(10).toMillis()
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entity = DatabaseData.Entity(
      RawEntity(
        "entity",
        singletons = mapOf("text" to "def".toReferencable()),
        collections = mapOf("refs" to setOf(RawReference("nested", backingKey, VERSION_MAP))),
        creationTimestamp = JvmTime.currentTimeMillis - ArcsDuration.ofDays(10).toMillis()
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    suspend fun updateCollection(vararg entities: DatabaseData.Entity) {
      val values = entities.map {
        ReferenceWithVersion(
          RawReference(it.rawEntity.id, backingKey, VersionMap("ref" to 1)),
          VersionMap("actor" to 1)
        )
      }
      val collection = DatabaseData.Collection(
        values = values.toSet(),
        schema = schema,
        databaseVersion = version++,
        versionMap = VERSION_MAP
      )
      database.insertOrUpdate(DummyStorageKey("collection"), collection)
    }

    database.insertOrUpdate(nestedKey, nested)
    database.insertOrUpdate(entityKey, entity)
    // Insert in collection.
    updateCollection(entity)
    // Remove from collection.
    updateCollection()

    // First run, entity is detected as orphan.
    database.runGarbageCollection()
    assertThat(database.getEntity(entityKey, schema)).isEqualTo(entity)
    assertThat(readOrphanField(entityKey)).isTrue()

    // Second run, entity is removed, nested entity is still in the db.
    database.runGarbageCollection()
    assertThat(database.getEntity(entityKey, schema)).isEqualTo(null)
    assertThat(database.getEntity(nestedKey, schema)).isEqualTo(nested)

    // Next run, nested is marked as orphan.
    database.runGarbageCollection()
    assertThat(readOrphanField(nestedKey)).isTrue()

    // Finally, nested gets removed.
    database.runGarbageCollection()
    assertThat(database.getEntity(nestedKey, schema)).isEqualTo(null)
  }

  @Test
  fun garbageCollectionEntityWithNestedEntityRemovedFromSingleton() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(
        collections = mapOf("texts" to FieldType.Text),
        singletons = mapOf("ref" to FieldType.EntityRef("hash"))
      )
    )
    val backingKey = DummyStorageKey("backing")
    val entityKey = DummyStorageKey("backing/entity")
    val nestedKey = DummyStorageKey("backing/nested")
    var version = 1
    val nested = DatabaseData.Entity(
      RawEntity(
        "nested",
        singletons = mapOf("ref" to null),
        collections = mapOf("texts" to setOf("abc".toReferencable())),
        creationTimestamp = JvmTime.currentTimeMillis - ArcsDuration.ofDays(10).toMillis()
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entity = DatabaseData.Entity(
      RawEntity(
        "entity",
        singletons = mapOf("ref" to RawReference("nested", backingKey, VERSION_MAP)),
        collections = mapOf("texts" to setOf("def".toReferencable())),
        creationTimestamp = JvmTime.currentTimeMillis - ArcsDuration.ofDays(10).toMillis()
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    suspend fun updateSingleton(entity: DatabaseData.Entity?) {
      val ref = entity?.let {
        ReferenceWithVersion(
          RawReference(it.rawEntity.id, backingKey, VersionMap("ref" to 1)),
          VersionMap("actor" to 1)
        )
      }
      val singleton = DatabaseData.Singleton(
        value = ref,
        schema = schema,
        databaseVersion = version++,
        versionMap = VERSION_MAP
      )
      database.insertOrUpdate(DummyStorageKey("singleton"), singleton)
    }

    database.insertOrUpdate(nestedKey, nested)
    database.insertOrUpdate(entityKey, entity)
    // Insert in singleton.
    updateSingleton(entity)
    // Remove from singleton.
    updateSingleton(null)

    // First run, entity is detected as orphan.
    database.runGarbageCollection()
    assertThat(database.getEntity(entityKey, schema)).isEqualTo(entity)
    assertThat(readOrphanField(entityKey)).isTrue()

    // Second run, entity is removed, nested entity is still in the db.
    database.runGarbageCollection()
    assertThat(database.getEntity(entityKey, schema)).isEqualTo(null)
    assertThat(database.getEntity(nestedKey, schema)).isEqualTo(nested)

    // Next run, nested is marked as orphan.
    database.runGarbageCollection()
    assertThat(readOrphanField(nestedKey)).isTrue()

    // Finally, nested gets removed.
    database.runGarbageCollection()
    assertThat(database.getEntity(nestedKey, schema)).isEqualTo(null)
  }

  // Tests that getEntity does not fail if the data that's being read is modified by GC
  // during the read.
  @Test
  fun garbageCollection_doesntBreak_entityReads() = runBlocking {
    val entity = fixtureEntities.generate().toDatabaseData()

    val key = DummyStorageKey("entity")
    database.insertOrUpdate(key, entity)

    database.runGarbageCollection()
    assertThat(database.getEntity(key, entity.schema)).isEqualTo(entity)
    assertThat(readOrphanField(key)).isTrue()

    var collected = false

    // run GC in parallel to a read
    val job = launch(newSingleThreadContext("GC")) {
      database.runGarbageCollection()
      collected = true
    }

    while (!collected) {
      database.getEntity(key, entity.schema)
    }

    job.join()
  }

  // Tests that getEntity does not fail if the data that's being read is modified by TTL
  // expiration during the read.
  @Test
  fun expiredEntityClearing_doesntBreak_entityReads() = runBlocking {
    val entity = fixtureEntities.generate(
      "alreadyExpired",
      11L,
      JvmTime.currentTimeMillis - 100000
    ).toDatabaseData()

    val key = DummyStorageKey("entity")
    database.insertOrUpdate(key, entity)

    var removed = false

    val job = launch(newSingleThreadContext("TTL")) {
      database.removeExpiredEntities()
      removed = true
    }

    while (!removed) {
      database.getEntity(key, entity.schema)
    }

    job.join()
  }

  @Test
  fun aMixtureOfGCAndClearing_doesntBreak_entityReads() = runBlocking {
    val total_seconds = 10

    val startTime = JvmTime.currentTimeMillis + 15000 // takes about 15 seconds to set up
    val collectionKey = DummyStorageKey("collection")

    // Set up a few entities that will expire as the test proceeds. We don't want these all
    // to expire at the same time; so this is somewhat spaced out.
    val dueToExpire = listOf(1000, 1100, 1200, 1300, 1400, 5000, 5100, 5200, 9800, 9900, 10000)
      .map {
        DummyStorageKey("expiresIn$it") to fixtureEntities.generate(
          "expiresIn$it",
          11L,
          startTime + it
        ).toDatabaseData()
      }.onEach { (key, entity) -> database.insertOrUpdate(key, entity) }

    // Set up a few entities that will be removed over time as the test proceeds.
    val toBeRemoved = (1..total_seconds).map {
      DummyStorageKey("removed$it") to fixtureEntities.generate().toDatabaseData()
    }.onEach { (key, entity) -> database.insertOrUpdate(key, entity) }

    val actor = "actor"
    val toBeRemovedReferences = toBeRemoved.map { (key, entity) ->
      ReferenceWithVersion(
        RawReference("${entity.rawEntity.id}", key, VersionMap()),
        VersionMap(actor to 1)
      )
    }.toMutableList()

    val dueToExpireReferences = dueToExpire.map { (key, entity) ->
      ReferenceWithVersion(
        RawReference("${entity.rawEntity.id}", key, VersionMap()),
        VersionMap(actor to 1)
      )
    }

    val references = (toBeRemovedReferences + dueToExpireReferences).toMutableSet()

    // Add references to all entities to a collection, to pin them as not GC-able initially.
    // A "remover" thread below will pull some of these out of the collection progressively,
    // which will expose them to GC.
    var collection = DatabaseData.Collection(
      values = references,
      schema = dueToExpire[0].second.schema,
      databaseVersion = 1,
      versionMap = VersionMap(actor to 1)
    )

    database.insertOrUpdate(collectionKey, collection)

    var running = true

    // Launch a thread that repeatedly runs GC and TTL expiration. We want these to
    // coincide with getEntity calls at least a few times during the run, to check for
    // conflict between the two.
    val job = launch(Dispatchers.Default) {
      while (running) {
        database.runGarbageCollection()
        database.removeExpiredEntities()
        // Delay is required here, otherwise this process seems to starve out the others
        // and the test times out.
        delay(50)
      }
    }

    // Launch a thread that occasionally removes an entity from the collection; this renders
    // the entity able to be labeled as an orphan and eventually GC'd.
    val job2 = launch(Dispatchers.Default) {
      repeat(total_seconds) {
        val ref = toBeRemovedReferences.random()
        toBeRemovedReferences.remove(ref)
        references.remove(ref)
        collection = collection.copy(references, databaseVersion = collection.databaseVersion + 1)
        database.insertOrUpdate(collectionKey, collection)
        delay(1000)
      }
      running = false
    }

    // Call getEntity in a tight loop. We want this to collide with GC/TTL.
    while (running) {
      dueToExpire.forEach { (key, entity) -> database.getEntity(key, entity.schema) }
      toBeRemoved.forEach { (key, entity) -> database.getEntity(key, entity.schema) }
    }

    job.join()
    job2.join()
  }

  @Test
  fun removeExpiredEntities_entityIsCleared() = runBlockingTest {
    FixtureEntities.registerSchemas()
    val schema = FixtureEntity.SCHEMA
    val collectionKey = DummyStorageKey("collection")
    val expiredEntityId = "expiredEntity"
    val entity1Id = "entity1"
    val entity2Id = "entity2"
    val backingKey = DummyStorageKey("backing")
    val expiredEntityKey = DummyStorageKey("backing/$expiredEntityId")
    val entity1Key = DummyStorageKey("backing/$entity1Id")
    val entity2Key = DummyStorageKey("backing/$entity2Id")
    val timeInPast = JvmTime.currentTimeMillis - 1000000
    val timeInFuture = JvmTime.currentTimeMillis + 1000000

    // An expired entity.
    val expiredEntity = fixtureEntities.generate(
      entityId = expiredEntityId,
      creationTimestamp = 11L,
      expirationTimestamp = timeInPast
    ).toDatabaseData()

    // Add a not-yet-expired entity.
    val entity1 = fixtureEntities.generate(
      entityId = entity1Id,
      creationTimestamp = 11L,
      expirationTimestamp = timeInFuture
    ).toDatabaseData()

    // Add an entity with no expiration.
    val entity2 = fixtureEntities.generate(
      entityId = entity2Id,
      creationTimestamp = 11L,
      expirationTimestamp = null
    ).toDatabaseData()

    // Add all of them to a collection.
    val values = setOf(
      ReferenceWithVersion(
        RawReference(entity1Id, backingKey, VersionMap("ref" to 1)),
        VersionMap("actor" to 1)
      ),
      ReferenceWithVersion(
        RawReference(expiredEntityId, backingKey, VersionMap("ref-to-remove" to 2)),
        VersionMap("actor" to 2)
      ),
      ReferenceWithVersion(
        RawReference(entity2Id, backingKey, VersionMap("ref" to 1)),
        VersionMap("actor" to 3)
      )
    )
    val collection = DatabaseData.Collection(
      values = values,
      schema = schema,
      databaseVersion = FIRST_VERSION_NUMBER,
      versionMap = VERSION_MAP
    )

    database.insertOrUpdate(expiredEntityKey, expiredEntity)
    database.insertOrUpdate(entity1Key, entity1)
    database.insertOrUpdate(entity2Key, entity2)
    database.insertOrUpdate(collectionKey, collection)

    // Add clients to verify updates.
    val collectionClient = FakeDatabaseClient(collectionKey)
    database.addClient(collectionClient)
    val entityClient = FakeDatabaseClient(entity1Key)
    database.addClient(entityClient)
    val expiredEntityClient = FakeDatabaseClient(expiredEntityKey)
    database.addClient(expiredEntityClient)

    database.removeExpiredEntities()

    // Check the expired entity fields have been cleared (only a tombstone is left).
    assertThat(database.getEntity(expiredEntityKey, schema)).isEqualTo(expiredEntity.nulled())

    // Check the other entities have not been modified.
    assertThat(database.getEntity(entity1Key, schema)).isEqualTo(entity1)
    assertThat(database.getEntity(entity2Key, schema)).isEqualTo(entity2)

    // Check the collection only contain the non expired entities.
    val newValues = setOf(
      ReferenceWithVersion(
        RawReference(entity1Id, backingKey, VersionMap("ref" to 1)),
        VersionMap("actor" to 1)
      ),
      ReferenceWithVersion(
        RawReference(entity2Id, backingKey, VersionMap("ref" to 1)),
        VersionMap("actor" to 3)
      )
    )
    assertThat(database.getCollection(collectionKey, schema))
      .isEqualTo(collection.copy(values = newValues, databaseVersion = 2))

    // Check the expired entity ref is gone (there will be IDs for referenced/inner entities too).
    val entityIds = readEntityRefsEntityId()
    assertThat(entityIds).doesNotContain(expiredEntityId)
    assertThat(entityIds).containsAtLeast(entity1Id, entity2Id)

    // Check unused primitive values have been removed.
    val expiredFieldValues = expiredEntity.rawEntity.allData.map { it.value }.toList()
    assertThat(readTextPrimitiveValues()).containsNoneIn(expiredFieldValues)
    assertThat(readNumberPrimitiveValues()).containsNoneIn(expiredFieldValues)

    // Check stats after removing expired entities.
    assertTableIsSize(
      "collections",
      // Collections from the fields of 2 entities, plus 1 actual collection.
      FixtureEntities.DB_COLLECTIONS_PER_FIXTURE_ENTITY * 2 + 1
    )
    assertTableIsSize(
      "collection_entries",
      // Collection entries from the fields of the 2 entities, plus the 2 entities themselves.
      FixtureEntities.DB_COLLECTION_ENTRIES_PER_FIXTURE_ENTITY * 2 + 2
    )
    assertTableIsSize(
      "field_values",
      // Field values for the 2 entities.
      FixtureEntities.DB_FIELD_VALUES_PER_FIXTURE_ENTITY * 2
    )

    // Check the correct clients were notified.
    collectionClient.eventMutex.withLock {
      assertThat(collectionClient.deletes).containsExactly(null)
    }
    expiredEntityClient.eventMutex.withLock {
      assertThat(expiredEntityClient.deletes).containsExactly(null)
    }
    entityClient.eventMutex.withLock {
      assertThat(entityClient.deletes).isEmpty()
    }
  }

  @Test
  fun removeExpiredEntities_entityInSingleton() = runBlockingTest {
    FixtureEntities.registerSchemas()
    val schema = FixtureEntity.SCHEMA
    val singletonKey = DummyStorageKey("singleton")
    val backingKey = DummyStorageKey("backing")
    val entityKey = DummyStorageKey("backing/entity")
    val expiredEntityKey = DummyStorageKey("backing/expiredEntity")
    val timeInPast = JvmTime.currentTimeMillis - 1000000
    val timeInFuture = JvmTime.currentTimeMillis + 1000000

    // An expired entity.
    val expiredEntity = fixtureEntities.generate(
      entityId = "expiredEntity",
      creationTimestamp = 11L,
      expirationTimestamp = timeInPast
    ).toDatabaseData()

    // Add a not-yet-expired entity.
    val entity = fixtureEntities.generate(
      entityId = "entity",
      creationTimestamp = 11L,
      expirationTimestamp = timeInFuture
    ).toDatabaseData()

    // Singleton with expired entity.
    val singleton = DatabaseData.Singleton(
      value = ReferenceWithVersion(
        RawReference("expiredEntity", backingKey, VersionMap("ref-to-remove" to 2)),
        VersionMap("actor" to 2)
      ),
      schema = schema,
      databaseVersion = FIRST_VERSION_NUMBER,
      versionMap = VERSION_MAP
    )

    database.insertOrUpdate(expiredEntityKey, expiredEntity)
    database.insertOrUpdate(entityKey, entity)
    database.insertOrUpdate(singletonKey, singleton)

    // Add clients to verify updates.
    val singletonClient = FakeDatabaseClient(singletonKey)
    database.addClient(singletonClient)
    val entityClient = FakeDatabaseClient(entityKey)
    database.addClient(entityClient)
    val expiredEntityClient = FakeDatabaseClient(expiredEntityKey)
    database.addClient(expiredEntityClient)

    database.removeExpiredEntities()

    // Check the expired entity fields have been cleared (only a tombstone is left).
    assertThat(database.getEntity(expiredEntityKey, schema)).isEqualTo(expiredEntity.nulled())

    // Check the other entity has not been modified.
    assertThat(database.getEntity(entityKey, schema)).isEqualTo(entity)

    // Check the singleton now contains null.
    assertThat(database.getSingleton(singletonKey, schema))
      .isEqualTo(singleton.copy(value = null, databaseVersion = 2))

    // Check the corrent clients were notified.
    singletonClient.eventMutex.withLock {
      assertThat(singletonClient.deletes).containsExactly(null)
    }
    expiredEntityClient.eventMutex.withLock {
      assertThat(expiredEntityClient.deletes).containsExactly(null)
    }
    entityClient.eventMutex.withLock {
      assertThat(entityClient.deletes).isEmpty()
    }

    // Change the singleton to point to the non-expired entity.
    val singleton2 = singleton.copy(
      value = ReferenceWithVersion(
        RawReference("entity", backingKey, VersionMap("ref" to 1)),
        VersionMap("actor" to 2)
      ),
      databaseVersion = FIRST_VERSION_NUMBER + 2
    )
    database.insertOrUpdate(singletonKey, singleton2)

    database.removeExpiredEntities()

    // Nothing should change.
    assertThat(database.getSingleton(singletonKey, schema)).isEqualTo(singleton2)
    assertThat(database.getEntity(expiredEntityKey, schema)).isEqualTo(expiredEntity.nulled())
    assertThat(database.getEntity(entityKey, schema)).isEqualTo(entity)
  }

  @Test
  fun removeExpiredEntities_twoEntitiesExpired() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("text" to FieldType.Text),
        collections = mapOf("nums" to FieldType.Number)
      )
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val entity1Key = DummyStorageKey("backing/entity1")
    val entity2Key = DummyStorageKey("backing/entity2")

    val timeInPast = JvmTime.currentTimeMillis - 10000
    val entity1 = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("text" to "abc".toReferencable()),
        mapOf("nums" to setOf(123.0.toReferencable(), 456.0.toReferencable())),
        11L,
        timeInPast // expirationTimestamp, in the past.
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entity2 = DatabaseData.Entity(
      RawEntity(
        "entity2",
        mapOf("text" to "def".toReferencable()),
        mapOf("nums" to setOf(123.0.toReferencable(), 789.0.toReferencable())),
        11L,
        timeInPast // expirationTimestamp, in the past.
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    // Add both of them to a collection.
    val values = setOf(
      ReferenceWithVersion(
        RawReference("entity1", backingKey, VersionMap("ref1" to 1)),
        VersionMap("actor" to 1)
      ),
      ReferenceWithVersion(
        RawReference("entity2", backingKey, VersionMap("ref2" to 2)),
        VersionMap("actor" to 2)
      )
    )
    val collection = DatabaseData.Collection(
      values = values,
      schema = schema,
      databaseVersion = FIRST_VERSION_NUMBER,
      versionMap = VERSION_MAP
    )

    database.insertOrUpdate(entity1Key, entity1)
    database.insertOrUpdate(entity2Key, entity2)
    database.insertOrUpdate(collectionKey, collection)

    database.removeExpiredEntities()

    // Check the expired entities fields have been cleared (only a tombstone is left).
    assertThat(database.getEntity(entity1Key, schema))
      .isEqualTo(
        DatabaseData.Entity(
          RawEntity(
            "entity1",
            mapOf("text" to null),
            mapOf("nums" to emptySet()),
            11L,
            timeInPast
          ),
          schema,
          FIRST_VERSION_NUMBER,
          VERSION_MAP
        )
      )
    assertThat(database.getEntity(entity2Key, schema))
      .isEqualTo(
        DatabaseData.Entity(
          RawEntity(
            "entity2",
            mapOf("text" to null),
            mapOf("nums" to emptySet()),
            11L,
            timeInPast
          ),
          schema,
          FIRST_VERSION_NUMBER,
          VERSION_MAP
        )
      )

    // Check the collection is empty.
    assertThat(database.getCollection(collectionKey, schema))
      .isEqualTo(collection.copy(values = setOf(), databaseVersion = 2))

    // Check unused values have been deleted from the global table as well.
    assertTableIsEmpty("field_values")

    // Check collection entries have been cleared.
    assertTableIsEmpty("collection_entries")

    // Check the collections for nums are gone (the collection left is the entity collection).
    assertTableIsSize("collections", 1)

    // Check the entity refs are gone.
    assertTableIsEmpty("entity_refs")

    // Check unused primitive values have been removed.
    assertTableIsEmpty("text_primitive_values")
    assertTableIsEmpty("number_primitive_values")
  }

  @Test
  fun removeLargeNumberOfEntities_respectSqliteParametersLimit() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(singletons = mapOf("num" to FieldType.Int), collections = mapOf())
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val references = mutableSetOf<ReferenceWithVersion>()
    for (i in 1..1100) {
      val id = "entity$i"
      database.insertOrUpdate(
        DummyStorageKey("backing/$id"),
        RawEntity(id, mapOf("num" to i.toReferencable())).toDatabaseData(schema)
      )
      references.add(
        ReferenceWithVersion(
          RawReference(id, backingKey, VersionMap("ref1" to i)),
          VersionMap("actor" to i)
        )
      )
    }
    val collection = DatabaseData.Collection(
      values = references,
      schema = schema,
      databaseVersion = FIRST_VERSION_NUMBER,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdate(collectionKey, collection)

    database.removeAllEntities()

    assertThat(database.getCollection(collectionKey, schema))
      .isEqualTo(collection.copy(values = emptySet(), databaseVersion = 2))
  }

  @Test
  fun removeExpiredReference() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(singletons = mapOf("text" to FieldType.Text), collections = mapOf())
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val entityKey = DummyStorageKey("backing/entity")
    val entity2Key = DummyStorageKey("backing/entity2")

    val entity = DatabaseData.Entity(
      RawEntity(
        "entity",
        mapOf("text" to "abc".toReferencable()),
        mapOf(),
        11L,
        JvmTime.currentTimeMillis + 10000 // expirationTimestamp, in the future.
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entity2 = DatabaseData.Entity(
      RawEntity(
        "entity2",
        mapOf("text" to "abc".toReferencable()),
        mapOf(),
        11L,
        JvmTime.currentTimeMillis + 10000 // expirationTimestamp, in the future.
      ),
      schema,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val timeInPast = JvmTime.currentTimeMillis - 10000
    val expiredRef = ReferenceWithVersion(
      RawReference("entity", backingKey, VersionMap("ref" to 1), 11L, timeInPast),
      VersionMap("actor" to 1)
    )

    val okRef = ReferenceWithVersion(
      RawReference("entity2", backingKey, VersionMap("ref" to 1), 12L),
      VersionMap("actor" to 2)
    ) // no expiration
    val collection = DatabaseData.Collection(
      values = setOf(expiredRef, okRef),
      schema = schema,
      databaseVersion = FIRST_VERSION_NUMBER,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdate(entityKey, entity)
    database.insertOrUpdate(entity2Key, entity2)
    database.insertOrUpdate(collectionKey, collection)

    // Add client to verify updates.
    val collectionClient = FakeDatabaseClient(collectionKey)
    database.addClient(collectionClient)

    database.removeExpiredEntities()

    // Check the entity itself has not been modified.
    assertThat(database.getEntity(entityKey, schema)).isEqualTo(entity)
    assertThat(database.getEntity(entity2Key, schema)).isEqualTo(entity2)

    // Check the collection only contain the non-expired reference.
    assertThat(database.getCollection(collectionKey, schema))
      .isEqualTo(collection.copy(values = setOf(okRef), databaseVersion = 2))

    // Check the expired entity ref is gone.
    assertThat(readEntityRefsEntityId()).containsExactly("entity2")

    // Check the corresponding collection entry is gone.
    assertTableIsSize("collection_entries", 1)

    // Check the client was notified.
    collectionClient.eventMutex.withLock {
      assertThat(collectionClient.deletes).containsExactly(null)
    }
  }

  @Test
  fun removeEntitiesReferencing() = runBlockingTest {
    newSchema("child")
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("ref" to FieldType.EntityRef("child")),
        collections = mapOf("refs" to FieldType.EntityRef("child"))
      )
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val entity1Key = DummyStorageKey("backing/entity1")
    val entity2Key = DummyStorageKey("backing/entity2")
    val entity3Key = DummyStorageKey("backing/entity3")
    val entity4Key = DummyStorageKey("backing/entity4")
    val foreignKey = DummyStorageKey("foreign")

    // Singleton reference field.
    val entity1 = RawEntity(
      "entity1",
      mapOf("ref" to RawReference("refId", foreignKey, null, isHardReference = true)),
      mapOf("refs" to emptySet())
    ).toDatabaseData(schema)
    // Field points to another reference id.
    val entity2 = RawEntity(
      "entity2",
      mapOf("ref" to RawReference("another-refId", foreignKey, null, isHardReference = true)),
      mapOf("refs" to emptySet())
    ).toDatabaseData(schema)
    // Collection field.
    val entity3 = RawEntity(
      "entity2",
      mapOf("ref" to null),
      mapOf(
        "refs" to setOf(
          RawReference("refId", foreignKey, null, isHardReference = true),
          RawReference("another-refId", foreignKey, null, isHardReference = true)
        )
      )
    ).toDatabaseData(schema)
    // Non-hard reference.
    val entity4 = RawEntity(
      "entity4",
      mapOf("ref" to RawReference("refId", foreignKey, null, isHardReference = false)),
      mapOf("refs" to emptySet())
    ).toDatabaseData(schema)
    val collection = dbCollection(backingKey, schema, entity1, entity2, entity3, entity4)

    database.insertOrUpdate(entity1Key, entity1)
    database.insertOrUpdate(entity2Key, entity2)
    database.insertOrUpdate(entity3Key, entity3)
    database.insertOrUpdate(entity4Key, entity4)
    database.insertOrUpdate(collectionKey, collection)

    assertThat(database.removeEntitiesHardReferencing(foreignKey, "refId")).isEqualTo(2)
    assertThat(database.getAllHardReferenceIds(foreignKey)).doesNotContain("refId")

    // Entities 1 and 3 should be cleared.
    assertThat(database.getEntity(entity1Key, schema)).isEqualTo(entity1.nulled())
    assertThat(database.getEntity(entity2Key, schema)).isEqualTo(entity2)
    assertThat(database.getEntity(entity3Key, schema)).isEqualTo(entity3.nulled())
    assertThat(database.getEntity(entity4Key, schema)).isEqualTo(entity4)

    // Only entity 2 and 4 are left in the collection.
    assertThat(database.getCollection(collectionKey, schema))
      .isEqualTo(dbCollection(backingKey, schema, entity2, entity4).copy(databaseVersion = 2))

    // Check that rerunning the method returns zero (entities are not double counted).
    assertThat(database.removeEntitiesHardReferencing(foreignKey, "refId")).isEqualTo(0)
  }

  @Test
  fun removeEntitiesReferencingInline() = runBlockingTest {
    newSchema("child")
    newSchema(
      "inlineInlineHash",
      SchemaFields(
        singletons = mapOf("ref" to FieldType.EntityRef("child")),
        collections = emptyMap()
      )
    )
    newSchema(
      "inlineHash",
      SchemaFields(
        singletons = mapOf("inline" to FieldType.InlineEntity("inlineInlineHash")),
        collections = emptyMap()
      )
    )
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("inline" to FieldType.InlineEntity("inlineHash")),
        collections = mapOf("inlines" to FieldType.InlineEntity("inlineHash"))
      )
    )

    val foreignKey = DummyStorageKey("foreign")
    val inlineInlineEntity1 = RawEntity(
      "iie1",
      singletons = mapOf("ref" to RawReference("refId", foreignKey, null, isHardReference = true))
    )
    val inlineInlineEntity2 = RawEntity(
      "iie2",
      singletons = mapOf("ref" to RawReference("refId2", foreignKey, null, isHardReference = true))
    )
    val inlineEntity1 = RawEntity(
      "ie1",
      singletons = mapOf("inline" to inlineInlineEntity1)
    )
    val inlineEntity2 = RawEntity(
      "ie2",
      singletons = mapOf("inline" to inlineInlineEntity2)
    )
    val entity = RawEntity(
      "entity",
      singletons = mapOf("inline" to inlineEntity1),
      collections = mapOf("inlines" to setOf(inlineEntity2))
    ).toDatabaseData(schema)

    val entityKey = DummyStorageKey("backing/entity")
    database.insertOrUpdate(entityKey, entity)
    val entity2 = RawEntity(
      "entity2",
      singletons = mapOf("inline" to null),
      collections = mapOf("inlines" to setOf(inlineEntity2))
    ).toDatabaseData(schema)

    val entity2Key = DummyStorageKey("backing/entity2")
    database.insertOrUpdate(entity2Key, entity2)

    assertThat(database.removeEntitiesHardReferencing(foreignKey, "refId")).isEqualTo(1)
    assertThat(database.getAllHardReferenceIds(foreignKey)).doesNotContain("refId")
    assertThat(database.getEntity(entityKey, schema)).isEqualTo(entity.nulled())
    assertThat(database.getEntity(entity2Key, schema)).isEqualTo(entity2)
  }

  @Test
  fun removeEntitiesReferencing_respectSqliteParametersLimit() = runBlockingTest {
    newSchema("child")
    newSchema(
      "inlineHash",
      SchemaFields(
        singletons = mapOf("ref" to FieldType.EntityRef("child")),
        collections = emptyMap()
      )
    )
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("inline" to FieldType.InlineEntity("inlineHash")),
        collections = emptyMap()
      )
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val foreignKey = DummyStorageKey("foreign")
    val inlineEntity = RawEntity(
      id = "ie",
      singletons = mapOf(
        "ref" to RawReference(
          id = "refId",
          storageKey = foreignKey,
          version = null,
          isHardReference = true
        )
      )
    )
    val references = mutableSetOf<ReferenceWithVersion>()
    for (i in 1..1100) {
      val id = "entity$i"
      val entity = RawEntity(id, mapOf("inline" to inlineEntity)).toDatabaseData(schema)
      database.insertOrUpdate(DummyStorageKey("backing/$id"), entity)
      references.add(
        ReferenceWithVersion(
          RawReference(id, backingKey, VersionMap("ref1" to i)),
          VersionMap("actor" to i)
        )
      )
    }
    val collection = DatabaseData.Collection(
      values = references,
      schema = schema,
      databaseVersion = FIRST_VERSION_NUMBER,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdate(collectionKey, collection)

    assertThat(database.removeEntitiesHardReferencing(foreignKey, "refId")).isEqualTo(1100)
    assertThat(database.getAllHardReferenceIds(foreignKey)).doesNotContain("refId")

    assertThat(database.getCollection(collectionKey, schema))
      .isEqualTo(collection.copy(values = setOf(), databaseVersion = 3))
  }

  @Test
  fun getAllHardReferenceIds() = runBlockingTest {
    newSchema("child")
    val schema = newSchema(
      "hash",
      SchemaFields(
        singletons = mapOf("ref" to FieldType.EntityRef("child")),
        collections = mapOf()
      )
    )
    val entity1Key = DummyStorageKey("backing/entity1")
    val entity2Key = DummyStorageKey("backing/entity2")
    val entity3Key = DummyStorageKey("backing/entity3")
    val entity4Key = DummyStorageKey("backing/entity4")
    val foreignKey = DummyStorageKey("foreign")
    val foreignKey2 = DummyStorageKey("foreign2")

    // Should be returned.
    val entity1 = RawEntity(
      singletons = mapOf("ref" to RawReference("id1", foreignKey, null, isHardReference = true))
    ).toDatabaseData(schema)
    // Should be returned.
    val entity2 = RawEntity(
      singletons = mapOf("ref" to RawReference("id2", foreignKey, null, isHardReference = true))
    ).toDatabaseData(schema)
    // Should not be returned, different storage key.
    val entity3 = RawEntity(
      singletons = mapOf("ref" to RawReference("id3", foreignKey2, null, isHardReference = true))
    ).toDatabaseData(schema)
    // Should not be returned, is not hard.
    val entity4 = RawEntity(
      singletons = mapOf("ref" to RawReference("id4", foreignKey, null, isHardReference = false))
    ).toDatabaseData(schema)

    database.insertOrUpdate(entity1Key, entity1)
    database.insertOrUpdate(entity2Key, entity2)
    database.insertOrUpdate(entity3Key, entity3)
    database.insertOrUpdate(entity4Key, entity4)

    assertThat(database.getAllHardReferenceIds(foreignKey))
      .containsExactly("id1", "id2")
  }

  @Test
  fun delete_entity_getsRemoved() = runBlockingTest {
    val entityKey = DummyStorageKey("entity")
    database.insertOrUpdateEntity(entityKey, EMPTY_ENTITY)

    database.delete(entityKey)

    assertTableIsEmpty("storage_keys")
    assertTableIsEmpty("entities")
    assertTableIsEmpty("field_values")
    assertThat(database.getEntity(entityKey, EMPTY_SCHEMA)).isNull()
  }

  @Test
  fun delete_throwsException_onInlineKeys() = runBlockingTest {
    val entityKey = DummyStorageKey("entity")
    val inlineKey = DatabaseImpl.Companion.InlineStorageKey(entityKey, "field")

    val exception = assertFailsWith<UnsupportedOperationException> {
      database.delete(inlineKey)
    }
    assertThat(exception).hasMessageThat().contains("Invalid attempt to delete inline storage key")
  }

  @Test
  fun delete_entityWithCollectionFields_getsRemoved() = runBlockingTest {
    val entity = DatabaseData.Entity(
      RawEntity(
        "entity",
        mapOf("text" to "def".toReferencable()),
        mapOf("nums" to setOf(123.0.toReferencable(), 789.0.toReferencable()))
      ),
      newSchema(
        "hash",
        SchemaFields(
          singletons = mapOf("text" to FieldType.Text),
          collections = mapOf("nums" to FieldType.Number)
        )
      ),
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    val entityKey = DummyStorageKey("entity")
    database.insertOrUpdateEntity(entityKey, entity)

    database.delete(entityKey)

    assertTableIsEmpty("storage_keys")
    assertTableIsEmpty("entities")
    assertTableIsEmpty("field_values")
    assertTableIsEmpty("collections")
    assertTableIsEmpty("collection_entries")
    assertThat(database.getEntity(entityKey, EMPTY_SCHEMA)).isNull()
  }

  @Test
  fun delete_entity_otherEntitiesUnaffected() = runBlockingTest {
    val keyToKeep = DummyStorageKey("key-to-keep")
    val keyToDelete = DummyStorageKey("key-to-delete")
    database.insertOrUpdateEntity(keyToKeep, EMPTY_ENTITY)
    database.insertOrUpdateEntity(keyToDelete, EMPTY_ENTITY)

    database.delete(keyToDelete)

    assertThat(database.getEntity(keyToKeep, EMPTY_SCHEMA)).isEqualTo(EMPTY_ENTITY)
    assertThat(database.getEntity(keyToDelete, EMPTY_SCHEMA)).isNull()
  }

  @Test
  fun delete_collection_getsRemoved() = runBlockingTest {
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val schema = newSchema("hash")
    val collection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(
          RawReference("ref1", backingKey, VersionMap("ref1" to 1)),
          VersionMap("actor" to 1)
        )
      ),
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdate(collectionKey, collection)

    database.delete(collectionKey)

    assertTableIsEmpty("storage_keys")
    assertTableIsEmpty("collections")
    assertTableIsEmpty("collection_entries")
    assertThat(database.getCollection(collectionKey, schema)).isNull()
  }

  @Test
  fun delete_collection_otherCollectionsUnaffected() = runBlockingTest {
    val keyToKeep = DummyStorageKey("key-to-keep")
    val keyToDelete = DummyStorageKey("key-to-delete")
    val backingKey = DummyStorageKey("backing")
    val schema = newSchema("hash")
    val collection = DatabaseData.Collection(
      values = setOf(
        ReferenceWithVersion(
          RawReference("ref1", backingKey, VersionMap("ref1" to 1)),
          VersionMap("actor" to 1)
        )
      ),
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdate(keyToKeep, collection)
    database.insertOrUpdate(keyToDelete, collection)

    database.delete(keyToDelete)

    assertThat(database.getCollection(keyToKeep, schema)).isEqualTo(collection)
    assertThat(database.getCollection(keyToDelete, schema)).isNull()
  }

  @Test
  fun delete_singleton_getsRemoved() = runBlockingTest {
    val singletonKey = DummyStorageKey("singleton")
    val backingKey = DummyStorageKey("backing")
    val schema = newSchema("hash")
    val singleton = DatabaseData.Singleton(
      value = ReferenceWithVersion(
        RawReference("ref1", backingKey, VersionMap("ref1" to 1)),
        VersionMap("actor" to 1)
      ),
      schema = schema,
      databaseVersion = 1,
      versionMap = VERSION_MAP
    )
    database.insertOrUpdateSingleton(singletonKey, singleton)

    database.delete(singletonKey)

    assertTableIsEmpty("storage_keys")
    assertTableIsEmpty("collections")
    assertTableIsEmpty("collection_entries")
    assertThat(database.getSingleton(singletonKey, schema)).isNull()
  }

  @Test
  fun delete_singleton_otherSingletonsUnaffected() = runBlockingTest {
    val keyToKeep = DummyStorageKey("key-to-keep")
    val keyToDelete = DummyStorageKey("key-to-delete")
    val backingKey = DummyStorageKey("backing")
    val schema = newSchema("hash")
    val reference = ReferenceWithVersion(
      RawReference("ref1", backingKey, VersionMap("ref1" to 1)),
      VersionMap("actor" to 1)
    )
    val singleton = DatabaseData.Singleton(reference, schema, 1, VERSION_MAP)
    database.insertOrUpdateSingleton(keyToKeep, singleton)
    database.insertOrUpdateSingleton(keyToDelete, singleton)

    database.delete(keyToDelete)

    assertThat(database.getSingleton(keyToKeep, schema)).isEqualTo(singleton)
    assertThat(database.getSingleton(keyToDelete, schema)).isNull()
  }

  @Test
  fun insertUpdate_notifiesCorrectClient() = runBlockingTest {
    val backingKey = DummyStorageKey("backing")
    val storageKeyA = DummyStorageKey("key-a")
    val storageKeyB = DummyStorageKey("key-b")
    val schema = newSchema("hash")

    val clientA = FakeDatabaseClient(storageKeyA)
    val clientAId = database.addClient(clientA)
    assertThat(clientAId).isEqualTo(1)

    val clientB = FakeDatabaseClient(storageKeyB)
    val clientBId = database.addClient(clientB)
    assertThat(clientBId).isEqualTo(2)

    val reference = ReferenceWithVersion(
      RawReference("ref1", backingKey, VersionMap("ref1" to 1)),
      VersionMap("actor" to 1)
    )
    val singleton = DatabaseData.Singleton(reference, schema, 1, VERSION_MAP)

    database.insertOrUpdate(storageKeyA, singleton, clientAId)

    clientA.eventMutex.withLock {
      assertThat(clientA.updates)
        .containsExactly(FakeDatabaseClient.Update(singleton, 1, clientAId))
    }
    clientB.eventMutex.withLock {
      assertThat(clientB.updates).isEmpty()
    }
  }

  @Test
  fun delete_notifiesCorrectClient() = runBlockingTest {
    val backingKey = DummyStorageKey("backing")
    val storageKeyA = DummyStorageKey("key-a")
    val storageKeyB = DummyStorageKey("key-b")
    val schema = newSchema("hash")

    val clientA = FakeDatabaseClient(storageKeyA)
    val clientAId = database.addClient(clientA)
    assertThat(clientAId).isEqualTo(1)

    val clientB = FakeDatabaseClient(storageKeyB)
    val clientBId = database.addClient(clientB)
    assertThat(clientBId).isEqualTo(2)

    val reference = ReferenceWithVersion(
      RawReference("ref1", backingKey, VersionMap("ref1" to 1)),
      VersionMap("actor" to 1)
    )
    val singleton = DatabaseData.Singleton(reference, schema, 1, VERSION_MAP)

    database.insertOrUpdate(storageKeyA, singleton, clientAId)
    database.delete(storageKeyA, clientAId)

    yield()

    clientA.eventMutex.withLock {
      assertThat(clientA.updates)
        .containsExactly(FakeDatabaseClient.Update(singleton, 1, clientAId))
      assertThat(clientA.deletes)
        .containsExactly(clientAId)
    }
    clientB.eventMutex.withLock {
      assertThat(clientB.updates).isEmpty()
      assertThat(clientB.deletes).isEmpty()
    }
  }

  @Test
  fun canAddAndRemoveClientsDuringClientCallback() = runBlockingTest {
    val otherClient = FakeDatabaseClient(STORAGE_KEY)
    val testClient = object : DatabaseClient {
      override val storageKey = STORAGE_KEY
      var updateWasCalled = false
      var deleteWasCalled = false
      var extraClientId: Int? = null

      override suspend fun onDatabaseUpdate(
        data: DatabaseData,
        version: Int,
        originatingClientId: Int?
      ) {
        updateWasCalled = true
        extraClientId = database.addClient(otherClient)
      }

      override suspend fun onDatabaseDelete(originatingClientId: Int?) {
        deleteWasCalled = true
        database.removeClient(extraClientId!!)
      }
    }

    // Add a bunch of fake clients before and after the one we're testing.
    repeat(5) { database.addClient(FakeDatabaseClient(STORAGE_KEY)) }
    database.addClient(testClient)
    repeat(5) { database.addClient(FakeDatabaseClient(STORAGE_KEY)) }

    // Issue an update and check it worked.
    database.insertOrUpdate(STORAGE_KEY, EMPTY_ENTITY)
    assertThat(testClient.updateWasCalled).isTrue()
    assertThat(testClient.extraClientId).isNotNull()

    // Issue a delete and check it worked.
    database.delete(STORAGE_KEY, originatingClientId = null)
    assertThat(testClient.deleteWasCalled).isTrue()
  }

  @Test
  fun canUpgradeSchema_byAddingNewFields() = runBlockingTest {
    val backingKey = DummyStorageKey("backing1")

    val entity = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("text" to "forty two".toReferencable()),
        emptyMap()
      ),
      SINGLE_FIELD_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    database.insertOrUpdate(backingKey, entity)
    assertThat(database.getEntity(backingKey, SINGLE_FIELD_SCHEMA)).isEqualTo(entity)

    // The existing entity is returned as if it belongs to the new schema,
    // but with a null value for the new field "number".
    val outputVersionOfEntity = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("text" to "forty two".toReferencable(), "number" to null),
        emptyMap()
      ),
      DOUBLE_FIELD_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    assertThat(
      database.getEntity(backingKey, DOUBLE_FIELD_SCHEMA)
    ).isEqualTo(outputVersionOfEntity)
  }

  @Test
  fun canUpgradeSchema_byRemovingFields() = runBlockingTest {
    val backingKey = DummyStorageKey("backing1")

    val entity = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("text" to "forty two".toReferencable(), "number" to 42.0.toReferencable()),
        emptyMap()
      ),
      DOUBLE_FIELD_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    SchemaRegistry.register(DOUBLE_FIELD_SCHEMA)
    SchemaRegistry.register(DOUBLE_FIELD_CONTAINER_SCHEMA)

    database.insertOrUpdate(backingKey, entity)
    assertThat(database.getEntity(backingKey, DOUBLE_FIELD_SCHEMA)).isEqualTo(entity)

    // The existing entity is returned as if it belongs to the new schema,
    // but with the legacy "number" field is still presented alongside the other fields.
    val outputVersionOfEntity = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("text" to "forty two".toReferencable(), "number" to 42.0.toReferencable()),
        emptyMap()
      ),
      SINGLE_FIELD_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    SchemaRegistry.register(SINGLE_FIELD_SCHEMA)
    SchemaRegistry.register(SINGLE_FIELD_CONTAINER_SCHEMA)

    assertThat(
      database.getEntity(backingKey, SINGLE_FIELD_SCHEMA)
    ).isEqualTo(outputVersionOfEntity)
  }

  @Test
  fun canUpgradeInlineSchema_byAddingNewFields() = runBlockingTest {
    val backingKey = DummyStorageKey("backing1")

    val inlineEntity = RawEntity(
      "",
      mapOf("text" to "forty two".toReferencable()),
      emptyMap()
    )

    val entity = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("inline" to inlineEntity),
        emptyMap()
      ),
      SINGLE_FIELD_CONTAINER_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    SchemaRegistry.register(SINGLE_FIELD_SCHEMA)
    SchemaRegistry.register(SINGLE_FIELD_CONTAINER_SCHEMA)

    database.insertOrUpdate(backingKey, entity)
    assertThat(database.getEntity(backingKey, SINGLE_FIELD_CONTAINER_SCHEMA)).isEqualTo(entity)

    // Note that in this case the returned inline entity doesn't contain a null
    // value for the new field number; though by the time it's returned it does
    // report as being of the new schema type.
    val outputVersionOfEntity = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("inline" to inlineEntity),
        emptyMap()
      ),
      DOUBLE_FIELD_CONTAINER_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    SchemaRegistry.register(DOUBLE_FIELD_SCHEMA)
    SchemaRegistry.register(DOUBLE_FIELD_CONTAINER_SCHEMA)

    assertThat(
      database.getEntity(backingKey, DOUBLE_FIELD_CONTAINER_SCHEMA)
    ).isEqualTo(outputVersionOfEntity)
  }

  @Test
  fun canUpgradeInlineSchema_byRemovingFields() = runBlockingTest {
    val backingKey = DummyStorageKey("backing1")

    val inlineEntity = RawEntity(
      "",
      mapOf("text" to "forty two".toReferencable(), "number" to 42.0.toReferencable()),
      emptyMap()
    )

    val entity = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("inline" to inlineEntity),
        emptyMap()
      ),
      DOUBLE_FIELD_CONTAINER_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    SchemaRegistry.register(DOUBLE_FIELD_SCHEMA)
    SchemaRegistry.register(DOUBLE_FIELD_CONTAINER_SCHEMA)

    database.insertOrUpdate(backingKey, entity)
    assertThat(database.getEntity(backingKey, DOUBLE_FIELD_CONTAINER_SCHEMA)).isEqualTo(entity)

    // When removing a field, the returned inline entity still contains the removed value
    // but reports as being of the new schema type.
    val outputVersionOfEntity = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("inline" to inlineEntity),
        emptyMap()
      ),
      SINGLE_FIELD_CONTAINER_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    SchemaRegistry.register(SINGLE_FIELD_SCHEMA)
    SchemaRegistry.register(SINGLE_FIELD_CONTAINER_SCHEMA)

    assertThat(
      database.getEntity(backingKey, SINGLE_FIELD_CONTAINER_SCHEMA)
    ).isEqualTo(outputVersionOfEntity)
  }

  @Test
  fun insertOrUpdate_mismatchedSchemaAndEntity_throws() = runBlockingTest {
    val backingKey = DummyStorageKey("backing1")

    val newEntity = DatabaseData.Entity(
      RawEntity(
        "entity2",
        mapOf("text" to "forty two".toReferencable(), "number" to 42.0.toReferencable()),
        emptyMap()
      ),
      SINGLE_FIELD_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    val exception = assertFailsWith<NoSuchElementException> {
      database.insertOrUpdate(backingKey, newEntity)
    }
    assertThat(exception).hasMessageThat().isEqualTo("Key number is missing in the map.")
  }

  @Test
  fun insertOrUpdate_schemaHashCollision_throws() = runBlockingTest {
    val backingKey1 = DummyStorageKey("backing1")
    val backingKey2 = DummyStorageKey("backing2")

    val schemaWithDuplicateHash = DOUBLE_FIELD_SCHEMA.copy(hash = SINGLE_FIELD_SCHEMA.hash)

    val entityForFirstSchema = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf("text" to "forty two".toReferencable()),
        emptyMap()
      ),
      SINGLE_FIELD_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    val entityForSecondSchema = DatabaseData.Entity(
      RawEntity(
        "entity2",
        mapOf("text" to "three".toReferencable(), "number" to 3.0.toReferencable()),
        emptyMap()
      ),
      schemaWithDuplicateHash,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    database.insertOrUpdate(backingKey1, entityForFirstSchema)

    val exception = assertFailsWith<NoSuchElementException> {
      database.insertOrUpdate(backingKey2, entityForSecondSchema)
    }
    assertThat(exception).hasMessageThat().isEqualTo("Key number is missing in the map.")
  }

  @Test
  fun getEntitiesCount() = runBlockingTest {
    val key1 = DummyStorageKey("key1")

    val schema1 = newSchema(
      "hash1",
      SchemaFields(
        singletons = mapOf(
          "textlist" to FieldType.ListOf(FieldType.Text),
          "longlist" to FieldType.ListOf(FieldType.Long),
          "nulltextlist" to FieldType.ListOf(FieldType.Text),
          "nulllonglist" to FieldType.ListOf(FieldType.Long)
        ),
        collections = emptyMap()
      )
    )

    val entity1 = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf(
          "textlist" to
            emptyList<ReferencablePrimitive<String>>()
              .toReferencable(FieldType.ListOf(FieldType.Text)),
          "longlist" to
            emptyList<ReferencablePrimitive<Long>>()
              .toReferencable(FieldType.ListOf(FieldType.Long)),
          "nulltextlist" to null,
          "nulllonglist" to null
        ),
        emptyMap()
      ),
      schema1,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    database.insertOrUpdateEntity(key1, entity1)
    assertThat(database.getEntitiesCount()).isEqualTo(1)

    val key2 = DummyStorageKey("key2")
    val schema2 = newSchema(
      "hash2",
      SchemaFields(
        singletons = mapOf(
          "textlist" to FieldType.ListOf(FieldType.Text),
          "nulllonglist" to FieldType.ListOf(FieldType.Long)
        ),
        collections = emptyMap()
      )
    )

    val entity2 = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf(
          "textlist" to
            emptyList<ReferencablePrimitive<String>>()
              .toReferencable(FieldType.ListOf(FieldType.Text)),
          "nulllonglist" to null
        ),
        emptyMap()
      ),
      schema2,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    database.insertOrUpdateEntity(key2, entity2)
    assertThat(database.getEntitiesCount()).isEqualTo(2)

    database.removeAllEntities()
    // GC twice as entities are marked as orphan the first time, removed the second time.
    database.runGarbageCollection()
    database.runGarbageCollection()
    assertThat(database.getEntitiesCount()).isEqualTo(0)
  }

  @Test
  fun getSize() = runBlockingTest {
    val initialSize = database.getSize()
    assertThat(initialSize).isGreaterThan(0)

    val key1 = DummyStorageKey("key1")
    val schema1 = newSchema(
      "hash1",
      SchemaFields(
        singletons = mapOf(
          "textlist" to FieldType.ListOf(FieldType.Text),
          "longlist" to FieldType.ListOf(FieldType.Long),
          "nulltextlist" to FieldType.ListOf(FieldType.Text),
          "nulllonglist" to FieldType.ListOf(FieldType.Long)
        ),
        collections = emptyMap()
      )
    )

    val entity1 = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf(
          "textlist" to
            emptyList<ReferencablePrimitive<String>>()
              .toReferencable(FieldType.ListOf(FieldType.Text)),
          "longlist" to
            emptyList<ReferencablePrimitive<Long>>()
              .toReferencable(FieldType.ListOf(FieldType.Long)),
          "nulltextlist" to null,
          "nulllonglist" to null
        ),
        emptyMap()
      ),
      schema1,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    database.insertOrUpdateEntity(key1, entity1)
    val sizeWithOneEntity = database.getSize()
    assertThat(sizeWithOneEntity).isAtLeast(initialSize)

    val key2 = DummyStorageKey("key2")
    val schema2 = newSchema(
      "hash2",
      SchemaFields(
        singletons = mapOf(
          "textlist" to FieldType.ListOf(FieldType.Text),
          "nulllonglist" to FieldType.ListOf(FieldType.Long)
        ),
        collections = emptyMap()
      )
    )

    val entity2 = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf(
          "textlist" to
            emptyList<ReferencablePrimitive<String>>()
              .toReferencable(FieldType.ListOf(FieldType.Text)),
          "nulllonglist" to null
        ),
        emptyMap()
      ),
      schema2,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    database.insertOrUpdateEntity(key2, entity2)
    val sizeWithTwoEntity = database.getSize()
    assertThat(sizeWithTwoEntity).isAtLeast(sizeWithOneEntity)

    database.removeAllEntities()
    // GC twice as entities are marked as orphan the first time, removed the second time.
    database.runGarbageCollection()
    database.runGarbageCollection()
    val sizeAfterGC = database.getSize()
    assertThat(sizeAfterGC).isAtMost(initialSize)
  }

  @Test
  fun insertEmptyEntityList() = runBlockingTest {
    val key1 = DummyStorageKey("key1")
    newSchema("inlineHash")
    val schema1 = newSchema(
      "hash1",
      SchemaFields(
        singletons = mapOf(
          "inlinelist" to FieldType.ListOf(FieldType.InlineEntity("inlineHash"))
        ),
        collections = emptyMap()
      )
    )

    val entity1 = DatabaseData.Entity(
      RawEntity(
        "entity1",
        mapOf(
          "inlinelist" to emptyList<RawEntity>().toReferencable(
            FieldType.ListOf(
              FieldType.InlineEntity(
                "inlineHash"
              )
            )
          )
        ),
        emptyMap()
      ),
      schema1,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
    database.insertOrUpdateEntity(key1, entity1)

    assertThat(
      database.getEntity(key1, schema1)
    ).isEqualTo(entity1)
  }

  @Test
  fun getSize_inMemoryDatabase() = runBlockingTest {
    // Makes sure in memory database can also return valid size.
    val inMemoryDatabase = DatabaseImpl(
      ApplicationProvider.getApplicationContext(),
      DummyStorageKeyManager(),
      "test.sqlite3",
      persistent = false
    )

    assertThat(inMemoryDatabase.getSize()).isGreaterThan(0)
    inMemoryDatabase.reset()
    inMemoryDatabase.close()
  }

  @Test
  fun collectionUpdate_tablesDontGrowUnbounded() = runBlockingTest {
    val schema = newSchema("hash")
    val backingKey = DummyStorageKey("backing")
    val collectionKey = DummyStorageKey("collection")
    var version = 1
    fun collection(vararg ids: String): DatabaseData.Collection {
      val values = ids.map {
        ReferenceWithVersion(
          RawReference(it, backingKey, VersionMap("ref" to 1)),
          VersionMap("actor" to 1)
        )
      }
      return DatabaseData.Collection(
        values = values.toSet(),
        schema = schema,
        databaseVersion = version++,
        versionMap = VersionMap("a" to version)
      )
    }

    database.insertOrUpdate(collectionKey, collection("1"))

    assertTableIsSize("entity_refs", 1)
    assertTableIsSize("collections", 1)
    assertTableIsSize("collection_entries", 1)
    assertTableIsSize("storage_keys", 1)

    database.insertOrUpdate(collectionKey, collection("1", "2"))

    assertTableIsSize("entity_refs", 2)
    assertTableIsSize("collections", 1)
    assertTableIsSize("collection_entries", 2)
    assertTableIsSize("storage_keys", 1)

    database.insertOrUpdate(collectionKey, collection("1", "2", "3"))

    assertTableIsSize("entity_refs", 3)
    assertTableIsSize("collections", 1)
    assertTableIsSize("collection_entries", 3)
    assertTableIsSize("storage_keys", 1)

    database.insertOrUpdate(collectionKey, collection("3"))

    // Old entity refs are still there, only cleaned by garbage collection.
    assertTableIsSize("entity_refs", 3)
    assertTableIsSize("collections", 1)
    assertTableIsSize("collection_entries", 1)
    assertTableIsSize("storage_keys", 1)

    // Confirm garbage collection will remove those unused refs.
    database.runGarbageCollection()

    assertTableIsSize("entity_refs", 1)
  }

  @Test
  fun getEntityReferenceId() = runBlockingTest {
    val backingKey = DummyStorageKey("backing")
    var reference = RawReference("id", backingKey, VersionMap("a" to 1))

    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(1)
    // Same reference again, should not create a new ID.
    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(1)

    // Different entity ID.
    reference = reference.copy(id = "id2")

    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(2)
    // Same reference again, same ID.
    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(2)

    // Different storage key.
    reference = reference.copy(storageKey = DummyStorageKey("2"))

    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(3)
    // Same reference again, same ID.
    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(3)

    // Different versionMap.
    reference = reference.copy(version = VersionMap("b" to 1))

    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(4)
    // Same reference again, same ID.
    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(4)

    // No versionMap.
    reference = reference.copy(version = null)

    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(5)
    // Same reference again, same ID.
    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(5)

    // Hard ref.
    reference = reference.copy(isHardReference = true)

    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(6)
    // Same reference again, same ID.
    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(6)

    // Creation timestamp.
    reference = reference.copy(_creationTimestamp = 123)

    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(7)
    // Same reference again, same ID.
    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(7)

    // Expiration timestamp.
    reference = reference.copy(_expirationTimestamp = 456)

    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(8)
    // Same reference again, same ID.
    assertThat(database.getEntityReferenceId(reference, db)).isEqualTo(8)
  }

  @Test
  fun databaseReset() = runBlockingTest {
    val schema = newSchema(
      "hash",
      SchemaFields(singletons = mapOf("num" to FieldType.Number), collections = emptyMap())
    )
    val collectionKey = DummyStorageKey("collection")
    val backingKey = DummyStorageKey("backing")
    val entityKey = DummyStorageKey("backing/entity")

    val entity = RawEntity("entity", mapOf("num" to 123.0.toReferencable())).toDatabaseData(schema)
    val collection = dbCollection(backingKey, schema, entity)

    database.insertOrUpdate(entityKey, entity)
    database.insertOrUpdate(collectionKey, collection)

    // This deletes both the entity and the collection.
    database.reset()

    assertThat(database.getCollection(collectionKey, schema)).isNull()
    assertThat(database.getEntity(entityKey, schema)).isNull()

    // Re-insert and read data to verify the db is still working after the reset.
    database.insertOrUpdate(entityKey, entity)
    database.insertOrUpdate(collectionKey, collection)

    assertThat(database.getCollection(collectionKey, schema)).isEqualTo(collection)
    assertThat(database.getEntity(entityKey, schema)).isEqualTo(entity)
  }

  @Test
  fun onDatabaseClose_calledWhenLastClientRemoved() = runBlockingTest {
    var onCloseCalled = false
    val database = DatabaseImpl(
      ApplicationProvider.getApplicationContext(),
      DummyStorageKeyManager(),
      "test.sqlite3",
      onDatabaseClose = {
        onCloseCalled = true
      }
    )
    val client1 = database.addClient(FakeDatabaseClient(DummyStorageKey("key1")))
    val client2 = database.addClient(FakeDatabaseClient(DummyStorageKey("key2")))
    val client3 = database.addClient(FakeDatabaseClient(DummyStorageKey("key3")))

    // Remove all clients except one.
    database.removeClient(client3)
    database.removeClient(client2)
    assertThat(onCloseCalled).isFalse()

    // Remove final client.
    database.removeClient(client1)
    assertThat(onCloseCalled).isTrue()
  }

  /** Returns a list of all the rows in the 'fields' table. */
  private fun readFieldsTable() =
    database.readableDatabase.rawQuery("SELECT * FROM fields", emptyArray()).map(::FieldRow)

  private fun readTextPrimitiveValues(): Set<String> =
    database.readableDatabase.rawQuery("SELECT value FROM text_primitive_values", emptyArray())
      .map { it.getString(0) }
      .toSet()

  private fun readNumberPrimitiveValues(): Set<Double> =
    database.readableDatabase
      .rawQuery("SELECT value FROM number_primitive_values", emptyArray())
      .map { it.getDouble(0) }
      .toSet()

  private fun readEntityRefsEntityId(): Set<String> =
    database.readableDatabase.rawQuery("SELECT entity_id FROM entity_refs", emptyArray())
      .map { it.getString(0) }
      .toSet()

  private fun assertTableIsSize(tableName: String, expectedSize: Int) {
    val actualSize = getTableSize(tableName)
    assertWithMessage(
      "Expected table $tableName to be of size $expectedSize, but found $actualSize rows."
    ).that(actualSize).isEqualTo(expectedSize)
  }

  private fun getTableSize(tableName: String): Int {
    return database.readableDatabase.rawQuery("SELECT * FROM $tableName", arrayOf()).use {
      it.count
    }
  }

  private fun readOrphanField(entityStorageKey: StorageKey): Boolean =
    database.readableDatabase.rawQuery(
      """
                SELECT orphan
                FROM entities
                LEFT JOIN storage_keys ON entities.storage_key_id = storage_keys.id
                WHERE storage_key = ?
      """.trimIndent(),
      arrayOf(entityStorageKey.toString())
    ).forSingleResult { it.getNullableBoolean(0) } ?: false

  private fun assertTableIsEmpty(tableName: String) {
    assertTableIsSize(tableName, 0)
  }

  private fun RawEntity.toDatabaseData(schema: Schema) = DatabaseData.Entity(
    this,
    schema,
    FIRST_VERSION_NUMBER,
    VERSION_MAP
  )

  private fun dbCollection(
    backingKey: StorageKey,
    schema: Schema,
    vararg entities: DatabaseData.Entity
  ): DatabaseData.Collection {
    val values = entities.map {
      ReferenceWithVersion(
        RawReference(it.rawEntity.id, backingKey, VersionMap("ref" to 1)),
        VersionMap("actor" to 1)
      )
    }
    return DatabaseData.Collection(
      values = values.toSet(),
      schema = schema,
      databaseVersion = FIRST_VERSION_NUMBER,
      versionMap = VERSION_MAP
    )
  }

  private fun DatabaseData.Entity.nulled(): DatabaseData.Entity =
    this.copy(
      rawEntity = rawEntity.copy(
        singletons = rawEntity.singletons.mapValues { null },
        collections = rawEntity.collections.mapValues { emptySet<Referencable>() }
      )
    )

  /** Helper that calls [DatabaseImpl.createEntityStorageKeyId] with a bunch of default params. */
  private suspend fun createEntityStorageKeyId(
    storageKey: StorageKey,
    entityId: String,
    databaseVersion: Int = FIRST_VERSION_NUMBER
  ): StorageKeyId? {
    return database.createEntityStorageKeyId(
      storageKey,
      entityId,
      CREATION_TIMESTAMP,
      EXPIRATION_TIMESTAMP,
      123L,
      VERSION_MAP,
      databaseVersion,
      db
    )
  }

  /** Converts a [FixtureEntity] to [DatabaseData.Entity]. */
  private fun FixtureEntity.toDatabaseData(): DatabaseData.Entity {
    return DatabaseData.Entity(
      serialize(),
      FixtureEntity.SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )
  }

  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    fun params() = ParameterizedBuildFlags.of("STORAGE_STRING_REDUCTION").toList()

    /** The first free Type ID after all primitive types have been assigned. */
    private const val FIRST_ENTITY_TYPE_ID = DatabaseImpl.REFERENCE_TYPE_SENTINEL + 1

    private const val FIRST_VERSION_NUMBER = 1
    private val VERSION_MAP = VersionMap("first" to 1, "second" to 2)

    private val TEXT_TYPE_ID = PrimitiveType.Text.ordinal.toLong()
    private val BOOLEAN_TYPE_ID = PrimitiveType.Boolean.ordinal.toLong()
    private val NUMBER_TYPE_ID = PrimitiveType.Number.ordinal.toLong()

    private val CREATION_TIMESTAMP = 99L
    private val EXPIRATION_TIMESTAMP = 999L

    private val STORAGE_KEY = DummyStorageKey("key")
    private val EMPTY_SCHEMA = newSchema("empty")
    private val EMPTY_RAW_ENTITY = RawEntity("empty-entity", emptyMap(), emptyMap())
    private val EMPTY_ENTITY = DatabaseData.Entity(
      EMPTY_RAW_ENTITY,
      EMPTY_SCHEMA,
      FIRST_VERSION_NUMBER,
      VERSION_MAP
    )

    private val SINGLE_FIELD_SCHEMA = newSchema(
      "_pre_upgrade_hash",
      SchemaFields(
        singletons = mapOf(
          "text" to FieldType.Text
        ),
        collections = emptyMap()
      )
    )

    private val DOUBLE_FIELD_SCHEMA = newSchema(
      "_post_upgrade_hash",
      SchemaFields(
        singletons = mapOf(
          "text" to FieldType.Text,
          "number" to FieldType.Number
        ),
        collections = emptyMap()
      )
    )

    private val SINGLE_FIELD_CONTAINER_SCHEMA = newSchema(
      "_pre_upgrade_container_hash",
      SchemaFields(
        singletons = mapOf(
          "inline" to FieldType.InlineEntity("_pre_upgrade_hash")
        ),
        collections = emptyMap()
      )
    )

    private val DOUBLE_FIELD_CONTAINER_SCHEMA = newSchema(
      "_post_upgrade_container_hash",
      SchemaFields(
        singletons = mapOf(
          "inline" to FieldType.InlineEntity("_post_upgrade_hash")
        ),
        collections = emptyMap()
      )
    )
  }
}

private fun newSchema(
  hash: String,
  fields: SchemaFields = SchemaFields(emptyMap(), emptyMap())
) = Schema(
  names = emptySet(),
  fields = fields,
  hash = hash
).also { SchemaRegistry.register(it) }

/** Helper class for reading results from the fields table. */
private data class FieldRow(
  val id: Long,
  val typeId: Long,
  val parentTypeId: Long,
  val name: String
) {
  constructor(cursor: Cursor) : this(
    cursor.getLong(0),
    cursor.getLong(1),
    cursor.getLong(2),
    cursor.getString(3)
  )
}

private class FakeDatabaseClient(override val storageKey: StorageKey) : DatabaseClient {
  val eventMutex = Mutex()
  val updates by guardedBy(eventMutex, mutableListOf<Update>())
  val deletes by guardedBy(eventMutex, mutableListOf<Int?>())

  override suspend fun onDatabaseUpdate(
    data: DatabaseData,
    version: Int,
    originatingClientId: Int?
  ) = eventMutex.withLock {
    updates.add(Update(data, version, originatingClientId))
    Unit
  }

  override suspend fun onDatabaseDelete(originatingClientId: Int?) = eventMutex.withLock {
    deletes.add(originatingClientId)
    Unit
  }

  data class Update(val data: DatabaseData, val version: Int, val originatingClientId: Int?)
}

/** Helper method to simplify fetching an entity from the database. */
private suspend fun DatabaseImpl.getEntity(
  storageKey: StorageKey,
  schema: Schema
): DatabaseData.Entity? {
  return get(storageKey, DatabaseData.Entity::class, schema) as DatabaseData.Entity?
}

/** Helper method to simplify fetching a singleton from the database. */
private suspend fun DatabaseImpl.getSingleton(
  storageKey: StorageKey,
  schema: Schema
): DatabaseData.Singleton? {
  return get(storageKey, DatabaseData.Singleton::class, schema) as DatabaseData.Singleton?
}

/** Helper method to simplify fetching a collection from the database. */
private suspend fun DatabaseImpl.getCollection(
  storageKey: StorageKey,
  schema: Schema
): DatabaseData.Collection? {
  return get(storageKey, DatabaseData.Collection::class, schema) as DatabaseData.Collection?
}
