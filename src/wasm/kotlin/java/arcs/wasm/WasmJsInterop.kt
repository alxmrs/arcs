package arcs.wasm

import arcs.*
import arcs.Collection
import arcs.AddressableMap.address2Addressable
import arcs.AddressableMap.addressable2Address
import arcs.AddressableMap.nextAddress
import kotlin.native.internal.ExportForCppRuntime
import kotlin.native.toUtf8
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.NativePtr
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.toLong

// Model WasmAddress as an Int
typealias WasmAddress = Address

// Wasm Strings are also Int heap pointers
typealias WasmString = Int

typealias WasmNullableString = Int

fun Any?.toAddress(): Address {
    // Null pointer maps to 0
    if (this == null) return 0

    return addressable2Address[this]?.let { it } ?: {
        val address = nextAddress++
        address2Addressable[address] = this
        addressable2Address[this] = address
        address
    }()
}

fun <T> Address?.toObject(): T? = if (this == 0) null else address2Addressable[this] as T

// Extension method to convert an Int into a Kotlin heap ptr
fun WasmAddress.toPtr(): NativePtr {
    return this.toLong().toCPointer<CPointed>()!!.rawValue
}

// Longs are only used for Kotlin-Native calls to ObjC/Desktop C targets
fun Long.toWasmAddress(): WasmAddress {
    return this.toInt()
}

// Convert a native Kotlin heap ptr to a WasmAddress
fun NativePtr.toWasmAddress(): WasmAddress {
    return this.toLong().toWasmAddress()
}

// Convert a WasmString pointer into a Kotlin String
fun WasmString.toKString(): String {
    return this.toLong().toCPointer<ByteVar>()!!.toKStringFromUtf8()
}

// Convert a WasmString pointer into a nullable Kotlin String
fun WasmNullableString.toNullableKString(): String? {
    return this.toLong().toCPointer<ByteVar>()?.toKStringFromUtf8()
}

@SymbolName("Kotlin_Arrays_getByteArrayAddressOfElement")
external fun ByteArray.addressOfElement(index: Int): CPointer<ByteVar>

/** Convert a Kotlin String into a WasmAddress */
fun String.toWasmString(): WasmString {
    // Ugh, this isn't null terminated
    val array = this.toUtf8()
    // So we have to make a copy to add a null
    val array2 = ByteArray(array.size + 1)
    array.copyInto(array2)
    array2[array.size] = 0.toByte()
    // When UTF16 is supported by CPP, we can remove all of this
    return array2.addressOfElement(0).toLong().toWasmAddress()
}

/** Convert a Kotlin String to a WasmAddress, where `null` is a valid value. */
fun String?.toWasmNullableString(): WasmNullableString {
    return this?.let { it.toWasmString() } ?: 0
}

// these are exported methods in the C++ runtime
@SymbolName("Kotlin_interop_malloc")
private external fun kotlinMalloc(size: Long, align: Int): NativePtr

@SymbolName("Kotlin_interop_free")
private external fun kotlinFree(ptr: NativePtr)

@SymbolName("abort")
external fun abort()

// Re-export the native C++ runtime methods to JS as _malloc/_free
@Retain
@ExportForCppRuntime("_malloc")
fun _malloc(size: Int): WasmAddress {
    return kotlinMalloc(size.toLong(), 1).toWasmAddress()
}

@Retain
@ExportForCppRuntime("_free")
fun _free(ptr: WasmAddress) {
    return kotlinFree(ptr.toPtr())
}

// //////////////////////////////////////// //
//  Global exports for WasmParticle follow  //
// //////////////////////////////////////// //

@Retain
@ExportForCppRuntime("_connectHandle")
fun connectHandle(
    particlePtr: WasmAddress,
    handleName: WasmString,
    canRead: Boolean,
    canWrite: Boolean
): WasmAddress {
    log("Connect called")
    return particlePtr
        .toObject<Particle>()
        ?.connectHandle(handleName.toKString(), canRead, canWrite)
        ?.toAddress() ?: 0
}

@Retain
@ExportForCppRuntime("_init")
fun init(particlePtr: WasmAddress) {
    particlePtr.toObject<Particle>()?.init()
}

@Retain
@ExportForCppRuntime("_syncHandle")
fun syncHandle(particlePtr: WasmAddress, handlePtr: WasmAddress, encoded: WasmNullableString) {
    log("Getting handle")
    val handle = handlePtr.toObject<Handle>()
    val encodedStr: String? = encoded.toNullableKString()
    handle?.let {
        log("Handle is '${handle.name}' syncing '$encodedStr'")
        log("Invoking sync on handle on particle")
        handle.sync(encodedStr)
        particlePtr.toObject<Particle>()?.sync(handle)
    }
}

@Retain
@ExportForCppRuntime("_updateHandle")
fun updateHandle(
    particlePtr: WasmAddress,
    handlePtr: WasmAddress,
    encoded1Ptr: WasmNullableString,
    encoded2Ptr: WasmNullableString
) {
    val handle = handlePtr.toObject<Handle>()
    handle?.let {
        it.update(encoded1Ptr.toNullableKString(), encoded2Ptr.toNullableKString())
        particlePtr.toObject<Particle>()?.onHandleUpdate(it)
    }
}

@Retain
@ExportForCppRuntime("_renderSlot")
fun renderSlot(
    particlePtr: WasmAddress,
    slotNamePtr: WasmString,
    sendTemplate: Boolean,
    sendModel: Boolean
) {
    particlePtr.toObject<Particle>()
        ?.renderSlot(slotNamePtr.toKString(), sendTemplate, sendModel)
}

@Retain
@ExportForCppRuntime("_fireEvent")
fun fireEvent(
    particlePtr: WasmAddress,
    slotNamePtr: WasmString,
    handlerNamePtr: WasmString,
    eventData: WasmString
) {
    particlePtr.toObject<Particle>()?.fireEvent(
        slotNamePtr.toKString(),
        handlerNamePtr.toKString(),
        StringDecoder.decodeDictionary(eventData.toKString())
    )
}

@Retain
@ExportForCppRuntime("_serviceResponse")
fun serviceResponse(
    particlePtr: WasmAddress,
    callPtr: WasmString,
    responsePtr: WasmString,
    tagPtr: WasmString
) {
    val dict = StringDecoder.decodeDictionary(responsePtr.toKString())
    particlePtr.toObject<Particle>()?.serviceResponse(callPtr.toKString(), dict, tagPtr.toKString())
}

@Retain
@ExportForCppRuntime("_renderOutput")
fun renderOutput(particlePtr: WasmAddress) {
    particlePtr.toObject<Particle>()
        ?.renderOutput()
}

@SymbolName("_singletonSet")
external fun singletonSet(particlePtr: WasmAddress, handlePtr: WasmAddress, stringPtr: WasmString)

@SymbolName("_singletonClear")
external fun singletonClear(particlePtr: WasmAddress, handlePtr: WasmAddress)

@SymbolName("_collectionStore")
external fun collectionStore(
    particlePtr: WasmAddress,
    handlePtr: WasmAddress,
    stringPtr: WasmString
): WasmString

@SymbolName("_collectionRemove")
external fun collectionRemove(
    particlePtr: WasmAddress,
    handlePtr: WasmAddress,
    stringPtr: WasmString
)

@SymbolName("_collectionClear")
external fun collectionClear(particlePtr: WasmAddress, handlePtr: WasmAddress)

@SymbolName("_render")
external fun render(
    particlePtr: WasmAddress,
    slotNamePtr: WasmString,
    templatePtr: WasmString,
    modelPtr: WasmString
)

@SymbolName("_onRenderOutput")
external fun onRenderOutput(
    particlePtr: WasmAddress,
    templatePtr: WasmNullableString,
    modelPtr: WasmNullableString
)

@SymbolName("_serviceRequest")
external fun serviceRequest(
    particlePtr: WasmAddress,
    callPtr: WasmString,
    argsPtr: WasmString,
    tagPtr: WasmString
)

@SymbolName("_resolveUrl")
external fun resolveUrl(urlPtr: WasmString): WasmString

@SymbolName("write")
external fun write(msg: WasmString)

@SymbolName("flush")
external fun flush()

fun log(msg: String) {
    write(msg.toWasmString())
    flush()
}
