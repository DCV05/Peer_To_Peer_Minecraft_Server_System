package endershare.link.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import endershare.link.net.ProtocolSniffer;
import io.netty.channel.Channel;

/**
 * Engancha el multiplexor al listener TCP del server: el initializer anonimo
 * de ServerNetworkIo#bind monta el pipeline vanilla y aqui se antepone el
 * sniffer que decide si la conexion es Minecraft o HTTP/WebSocket.
 */
@Mixin( targets = "net/minecraft/server/ServerNetworkIo$1" )
public abstract class ServerNetworkIoMixin
{

	@Inject( method = "initChannel", at = @At( "TAIL" ) )
	private void endershareLink$attachSniffer( Channel channel, CallbackInfo callbackInfo )
	{
		channel.pipeline().addFirst( ProtocolSniffer.NAME, new ProtocolSniffer() );
	}

}
