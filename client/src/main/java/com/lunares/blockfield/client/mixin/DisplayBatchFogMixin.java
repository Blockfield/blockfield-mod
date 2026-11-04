package com.lunares.blockfield.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lunares.blockfield.client.render.DisplayBatchFog;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Program.class)
public class DisplayBatchFogMixin implements DisplayBatchFog.Flag {
    @Unique private boolean blockfield$batchFog;

    @WrapMethod(method = "compileShader")
    private static Program blockfield$compileBatchFog(
            Program.Type type,
            String name,
            InputStream stream,
            String sourceName,
            GlslPreprocessor preprocessor,
            Operation<Program> original)
            throws IOException {
        if (type != Program.Type.VERTEX) {
            return original.call(type, name, stream, sourceName, preprocessor);
        }
        String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        String patched = DisplayBatchFog.patch(name, source);
        Program program =
                original.call(
                        type,
                        name,
                        new ByteArrayInputStream(patched.getBytes(StandardCharsets.UTF_8)),
                        sourceName,
                        preprocessor);
        ((DisplayBatchFog.Flag) program).blockfield$setBatchFog(patched != source);
        return program;
    }

    @Override
    public boolean blockfield$batchFog() {
        return blockfield$batchFog;
    }

    @Override
    public void blockfield$setBatchFog(boolean supported) {
        blockfield$batchFog = supported;
    }
}
