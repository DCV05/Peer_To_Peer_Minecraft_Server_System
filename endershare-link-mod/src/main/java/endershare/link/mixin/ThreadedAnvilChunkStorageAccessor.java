package endershare.link.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;

/** Acceso al iterador de chunk holders (protegido en vanilla). */
@Mixin( ThreadedAnvilChunkStorage.class )
public interface ThreadedAnvilChunkStorageAccessor
{

	@Invoker( "entryIterator" )
	Iterable<ChunkHolder> endershareLink$entryIterator();

}
