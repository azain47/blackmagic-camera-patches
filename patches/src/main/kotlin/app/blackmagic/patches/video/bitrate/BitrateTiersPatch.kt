package app.blackmagic.patches.video.bitrate

import app.blackmagic.patches.shared.Constants.COMPATIBILITY_BLACKMAGIC_CAM
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c

// 0.25f / 0.50f / 0.75f as raw 32-bit float bit patterns.
private const val SCALE_LOW = 0x3e800000   // 0.25f
private const val SCALE_MEDIUM = 0x3f000000 // 0.50f
private const val SCALE_HIGH = 0x3f400000   // 0.75f

@Suppress("unused")
val bitrateTiersPatch = bytecodePatch(
    name = "Configurable bitrate tiers",
    description = "Rescales the LOW / MEDIUM / HIGH video bitrate tiers to 25% / 50% / 75% of the " +
        "encoder's maximum (MAX is unchanged). At 1080p30 H.265 on a flagship this turns the stock " +
        "6 / 9 / 12 / 160 Mbps options into usable ~40 / 80 / 120 / 160 Mbps steps, removing the " +
        "order-of-magnitude gap between HIGH and MAX.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_BLACKMAGIC_CAM)

    execute {
        // --- Part 1: retune the enum scale constants in BitrateLevel.<clinit> ---
        // Stock: HIGH = 2.0, MEDIUM = 1.5, LOW = 1.0 (as multipliers of a base).
        // New:   HIGH = 0.75, MEDIUM = 0.50, LOW = 0.25 (as fractions of the ceiling).
        BitrateLevelClinitFingerprint.let { fp ->
            val (highIdx, mediumIdx, lowIdx) = fp.instructionMatches.map { it.index }

            fun retune(index: Int, newBits: Int) {
                val register = fp.method.getInstruction<OneRegisterInstruction>(index).registerA
                fp.method.replaceInstruction(index, "const v$register, $newBits")
            }
            // Replace from last index to first so indices stay valid (all 1:1 here, but kept for clarity).
            retune(highIdx, SCALE_HIGH)
            retune(mediumIdx, SCALE_MEDIUM)
            retune(lowIdx, SCALE_LOW)
        }

        // --- Part 2: rewrite the constant-frame-rate formula to use the encoder ceiling ---
        // Stock tail:  int-to-float p0, vRes ; iget p1,p3,BitrateLevel.f ; mul-float ;
        //              int-to-float p1, vBase ; mul-float ; invoke z64.w ; ... min(encoderMax, .)
        // New tail:    int-to-float p0, encoderMax ; iget p1,p3,BitrateLevel.f ; mul-float ;
        //              invoke z64.w ; ... min(encoderMax, .)
        // i.e. drop the resolution-base factor so the result is `round(encoderMax * scale)`.
        BitrateFormulaFingerprint.let { fp ->
            val igetIndex = fp.instructionMatches.first().index
            val method = fp.method

            // The Math.min(II)I call clamps to the encoder max; its first argument register is it.
            val minInsn = fp.instructionMatches[1].getInstruction<Instruction35c>()
            val encoderMaxReg = minInsn.registerC

            val intToFloat = method.getInstruction(igetIndex - 1)
            val stripA = method.getInstruction(igetIndex + 2)
            val stripB = method.getInstruction(igetIndex + 3)
            if (intToFloat.opcode != Opcode.INT_TO_FLOAT ||
                stripA.opcode != Opcode.INT_TO_FLOAT ||
                stripB.opcode != Opcode.MUL_FLOAT_2ADDR
            ) {
                throw PatchException("Unexpected bitrate formula shape; app internals changed.")
            }

            val accumulatorReg = (intToFloat as TwoRegisterInstruction).registerA
            // Feed the encoder ceiling into the accumulator instead of the resolution index.
            method.replaceInstruction(igetIndex - 1, "int-to-float v$accumulatorReg, v$encoderMaxReg")
            // Remove `int-to-float p1, vBase` + `mul-float/2addr p0, p1` (the resolution-base factor).
            method.removeInstructions(igetIndex + 2, 2)
        }
    }
}
