package com.maites.cookies.ui

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface ScanResult {
    data class Code(val value: String) : ScanResult
    data object Cancelled : ScanResult
    data class Failed(val message: String) : ScanResult
}

/** ML Kit code scanner (Google Play services UI, no camera permission needed). */
suspend fun scanBarcode(ctx: Context): ScanResult = suspendCancellableCoroutine { cont ->
    val opts = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128)
        .enableAutoZoom()
        .build()
    GmsBarcodeScanning.getClient(ctx, opts).startScan()
        .addOnSuccessListener { b -> if (cont.isActive) cont.resume(b.rawValue?.let { ScanResult.Code(it.trim()) } ?: ScanResult.Cancelled) }
        .addOnCanceledListener { if (cont.isActive) cont.resume(ScanResult.Cancelled) }
        .addOnFailureListener { e -> if (cont.isActive) cont.resume(ScanResult.Failed(e.message ?: "")) }
}
