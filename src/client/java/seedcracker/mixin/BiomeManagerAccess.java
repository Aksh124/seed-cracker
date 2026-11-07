package seedcracker.mixin;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The client keeps the server's hashed seed here, for biome blending. */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccess {
	@Accessor("biomeZoomSeed")
	long seedcracker$hashedSeed();
}
