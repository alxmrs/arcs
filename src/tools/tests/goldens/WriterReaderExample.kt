/* ktlint-disable */
@file:Suppress("PackageName", "TopLevelName")

package arcs.core.data.testdata

//
// GENERATED CODE -- DO NOT EDIT
//

import arcs.core.data.*
import arcs.core.storage.*

object IngestionPlan : Plan(
    listOf(
        Particle(
            "Writer",
            "",
            mapOf(
                "data" to HandleConnection(
                    StorageKeyParser.parse(""),
                    HandleMode.Write,
                    EntityType(Writer_Data_Spec.schema),
                    null
                )
            )
        )
    )
)
object ConsumptionPlan : Plan(
    listOf(
        Particle(
            "Reader",
            "",
            mapOf(
                "data" to HandleConnection(
                    StorageKeyParser.parse("ramdisk://"),
                    HandleMode.Read,
                    EntityType(Reader_Data_Spec.schema),
                    null
                )
            )
        )
    )
)
