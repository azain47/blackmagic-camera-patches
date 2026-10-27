package app.blackmagic.patches.gallery

import app.blackmagic.patches.shared.Constants.COMPATIBILITY_BLACKMAGIC_CAM
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val IGET_OPCODES = setOf(
    Opcode.IGET, Opcode.IGET_OBJECT, Opcode.IGET_WIDE, Opcode.IGET_BOOLEAN,
)

private fun FieldReference.smali() = "$definingClass->$name:$type"
private fun MethodReference.smali() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

@Suppress("unused")
val smoothGalleryThumbnailsPatch = bytecodePatch(
    name = "Smooth gallery thumbnails",
    description = "Replaces the in-app gallery's grid thumbnail fetcher, which spins up a full " +
        "ExoPlayer + GL-readback pipeline per cell (hundreds of MB of transient graphics memory " +
        "and a short-lived player for every tile), with a direct " +
        "MediaMetadataRetriever.getScaledFrameAtTime decode at the tile size. This removes the " +
        "memory spikes that make browsing stutter and can trigger the low-memory killer mid-record.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_BLACKMAGIC_CAM)

    execute {
        val method = FrameFetcherLoadDataFingerprint.method
        val impl = method.implementation ?: throw PatchException("fetcher has no body")

        // The callback (Glide DataCallback) is the second parameter.
        val callbackType = method.parameterTypes[1].toString()

        // Discover every obfuscated reference from the method's own bytecode, keyed
        // purely on stable (non-obfuscated) types, so renames across versions are tolerated.
        val fieldReads = impl.instructions
            .filter { it.opcode in IGET_OPCODES }
            .map { (it as ReferenceInstruction).reference as FieldReference }

        val contextField = fieldReads.first { it.type == "Landroid/content/Context;" }
        val modelField = fieldReads.first {
            it.definingClass == method.definingClass &&
                it.type != "Landroid/content/Context;" && it.type != "Z"
        }
        val modelType = modelField.type
        val uriField = fieldReads.first { it.definingClass == modelType && it.type == "Landroid/net/Uri;" }
        val intFields = fieldReads.filter { it.definingClass == modelType && it.type == "I" }.distinct()
        val posField = fieldReads.first { it.definingClass == modelType && it.type == "J" }
        if (intFields.size < 2) throw PatchException("gallery model shape changed (width/height)")
        val widthField = intFields[0]
        val heightField = intFields[1]

        val calls = impl.instructions
            .filter { it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_STATIC }
            .map { (it as ReferenceInstruction).reference as MethodReference }

        val deliver = calls.first {
            it.definingClass == callbackType && it.returnType == "V" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/Object;")
        }
        val onFailed = calls.first {
            it.definingClass == callbackType && it.returnType == "V" &&
                it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/Exception;")
        }
        val uriExists = calls.first {
            it.returnType == "Z" &&
                it.parameterTypes.map { p -> p.toString() } ==
                listOf("Landroid/content/Context;", "Landroid/net/Uri;")
        }

        // Prepend the replacement body. Every path returns before reaching the original
        // ExoPlayer-based extractor, so that pipeline never runs (it stays as unreachable
        // dead code, which avoids disturbing the original try/catch blocks).
        // Registers v0..v9 (method has 12 locals + params); getScaledFrameAtTime needs the
        // 6 contiguous registers v2..v7.
        method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, ${contextField.smali()}
                iget-object v1, p0, ${modelField.smali()}
                iget-object v8, v1, ${uriField.smali()}
                invoke-static { v0, v8 }, ${uriExists.smali()}
                move-result v9
                if-nez v9, :exists
                new-instance v9, Ljava/io/FileNotFoundException;
                invoke-direct { v9 }, Ljava/io/FileNotFoundException;-><init>()V
                invoke-interface { p2, v9 }, ${onFailed.smali()}
                return-void
                :exists
                new-instance v2, Landroid/media/MediaMetadataRetriever;
                invoke-direct { v2 }, Landroid/media/MediaMetadataRetriever;-><init>()V
                invoke-virtual { v2, v0, v8 }, Landroid/media/MediaMetadataRetriever;->setDataSource(Landroid/content/Context;Landroid/net/Uri;)V
                iget-wide v3, v1, ${posField.smali()}
                const-wide/16 v5, 0x3e8
                mul-long/2addr v3, v5
                const/4 v5, 0x2
                iget v6, v1, ${widthField.smali()}
                iget v7, v1, ${heightField.smali()}
                invoke-virtual/range { v2 .. v7 }, Landroid/media/MediaMetadataRetriever;->getScaledFrameAtTime(JIII)Landroid/graphics/Bitmap;
                move-result-object v9
                invoke-virtual { v2 }, Landroid/media/MediaMetadataRetriever;->release()V
                if-nez v9, :ok
                new-instance v9, Ljava/lang/Exception;
                invoke-direct { v9 }, Ljava/lang/Exception;-><init>()V
                invoke-interface { p2, v9 }, ${onFailed.smali()}
                return-void
                :ok
                invoke-interface { p2, v9 }, ${deliver.smali()}
                return-void
            """.trimIndent(),
        )
    }
}
