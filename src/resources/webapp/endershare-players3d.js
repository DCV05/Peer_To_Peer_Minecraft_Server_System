/*
 * Endershare: muñeco 3D del jugador dentro del visor del mapa.
 *
 * El visor solo sabe pintar una chincheta plana con la cara. Aqui se le cuelga
 * a esa chincheta un modelo de verdad —cabeza, cuerpo, brazos y piernas— con la
 * skin del jugador, orientado hacia donde mira.
 *
 * Va enganchado al propio visor, sin tocar su codigo: la chincheta ya es un
 * nodo de la escena 3D, asi que el modelo hereda su posicion y su animacion
 * gratis. Si algo de esto cambiara en una version futura del visor, el modelo
 * simplemente no aparece y la chincheta plana sigue funcionando.
 *
 * Este fichero se sirve pegado detras de three.js r147 —la MISMA version que usa
 * el visor— para que el orden de carga no dependa del navegador.
 */
(function ()
{
	"use strict";

	var THREE = window.THREE;
	if( !THREE )
		return;

	/** Un pixel de la skin, en bloques. El muñeco mide 32 px = 2 bloques. */
	var PIXEL = 1 / 16;
	/** La chincheta va a la altura de los ojos; el muñeco se cuelga por los pies. */
	var FEET_OFFSET = -1.8;
	/** Cada cuanto se reintenta enganchar al visor mientras arranca. */
	var RETRY_MS = 300;

	/**
	 * Cajas del modelo: [ancho, alto, fondo] en pixeles y su sitio en la skin.
	 *
	 * <p>Las cuentas antiguas tienen la skin en 64x32, la mitad de alto, y ahi el
	 * brazo y la pierna izquierdos NO existen: se dibujan con los del otro lado.
	 * Sin esto salen con la textura de fuera de la imagen, o sea un borron.</p>
	 */
	var PARTS = [
		{ name: "head", size: [ 8, 8, 8 ], at: [ 0, 28, 0 ], uv: [ 0, 0 ], old: [ 0, 0 ] },
		{ name: "body", size: [ 8, 12, 4 ], at: [ 0, 18, 0 ], uv: [ 16, 16 ], old: [ 16, 16 ] },
		{ name: "armRight", size: [ 4, 12, 4 ], at: [ -6, 18, 0 ], uv: [ 40, 16 ], old: [ 40, 16 ] },
		{ name: "armLeft", size: [ 4, 12, 4 ], at: [ 6, 18, 0 ], uv: [ 32, 48 ], old: [ 40, 16 ] },
		{ name: "legRight", size: [ 4, 12, 4 ], at: [ -2, 6, 0 ], uv: [ 0, 16 ], old: [ 0, 16 ] },
		{ name: "legLeft", size: [ 4, 12, 4 ], at: [ 2, 6, 0 ], uv: [ 16, 48 ], old: [ 0, 16 ] }
	];

	var models = Object.create( null );
	var textures = Object.create( null );
	var hooked = false;

	waitForViewer();

	function waitForViewer()
	{
		var viewer = window.bluemap;
		var manager = viewer && viewer.playerMarkerManager;
		var set = manager && manager.getPlayerMarkerSet && manager.getPlayerMarkerSet();
		if( !set )
		{
			window.setTimeout( waitForViewer, RETRY_MS );
			return;
		}
		hook( set );
	}

	/**
	 * El visor recarga la lista de jugadores una vez por segundo y la reparte por
	 * aqui. Nos colgamos de ese mismo momento: ni un temporizador mas, ni una
	 * peticion mas, y la orientacion llega ya leida.
	 */
	function hook( set )
	{
		var prototype = Object.getPrototypeOf( set );
		if( !hooked && prototype && typeof prototype.updateFromPlayerData === "function" )
		{
			var original = prototype.updateFromPlayerData;
			prototype.updateFromPlayerData = function ( data )
			{
				var result = original.call( this, data );
				try
				{
					refresh( this, data );
				}
				catch( failed )
				{
					// Un fallo aqui no puede tumbar la lista de jugadores del visor
					console.warn( "[endershare] modelo 3D:", failed );
				}
				return result;
			};
			hooked = true;
		}
		// Al cambiar de mapa el visor crea otro conjunto: se vacia lo de antes
		forgetAll();
	}

	function refresh( set, data )
	{
		if( !data || !Array.isArray( data.players ) )
		{
			forgetAll();
			return;
		}

		var seen = Object.create( null );
		for( var i = 0; i < data.players.length; i++ )
		{
			var player = data.players[i];
			if( !player || !player.uuid )
				continue;
			seen[player.uuid] = true;
			var marker = set.getPlayerMarker( player.uuid );
			if( marker )
				place( set, marker, player );
		}

		for( var uuid in models )
		{
			if( !seen[uuid] )
				forget( uuid );
		}
	}

	function place( set, marker, player )
	{
		var model = models[player.uuid];
		if( !model || model.marker !== marker )
		{
			forget( player.uuid );
			model = build( set, marker, player.uuid );
			if( !model )
				return;
			models[player.uuid] = model;
		}

		// El yaw de Minecraft cuenta al reves que el giro de la escena
		var yaw = player.rotation && typeof player.rotation.yaw === "number" ? player.rotation.yaw : 0;
		model.group.rotation.y = -yaw * Math.PI / 180;
	}

	function build( set, marker, uuid )
	{
		var texture = textureFor( set, uuid );
		// Sin skin no hay muñeco: se queda la chincheta plana, que es mejor que un
		// bulto negro con la textura sin cargar
		if( !texture )
			return null;

		var group = new THREE.Object3D();
		group.position.y = FEET_OFFSET;
		var material = new THREE.MeshBasicMaterial( { map: texture, transparent: true, alphaTest: 0.5 } );
		var image = texture.image;
		var height = image && image.height ? image.height : 64;
		// Las skins antiguas son de 64x32 y tienen medio cuerpo sin dibujar
		var oldStyle = height < 64;

		for( var i = 0; i < PARTS.length; i++ )
		{
			var part = PARTS[i];
			var corner = oldStyle ? part.old : part.uv;
			var geometry = new THREE.BoxGeometry( part.size[0] * PIXEL, part.size[1] * PIXEL,
					part.size[2] * PIXEL );
			applySkinUvs( geometry, corner[0], corner[1], part.size[0], part.size[1], part.size[2],
					image ? image.width : 64, height );
			var mesh = new THREE.Mesh( geometry, material );
			mesh.position.set( part.at[0] * PIXEL, part.at[1] * PIXEL, part.at[2] * PIXEL );
			group.add( mesh );
		}

		marker.add( group );
		// Con el muñeco puesto, la chincheta plana sobra; el nombre se queda
		if( marker.playerHeadElement )
			marker.playerHeadElement.style.display = "none";
		return { group: group, marker: marker, material: material };
	}

	/**
	 * La skin entera del jugador. La deja la aplicacion al lado de las caras.
	 *
	 * <p>La primera vez devuelve nada y se pide en segundo plano: cuando llegue,
	 * el muñeco se monta en la siguiente vuelta, un segundo despues. Si no llega
	 * —jugador sin skin, sin internet cuando se bajaron— se apunta y no se vuelve
	 * a pedir en toda la sesion.</p>
	 */
	function textureFor( set, uuid )
	{
		var known = textures[uuid];
		if( known !== undefined )
			return known;
		var heads = set.data && set.data.playerheadsUrl;
		if( !heads )
			return null;

		textures[uuid] = null;
		var url = heads.replace( "playerheads", "playerskins" ) + uuid + ".png";
		new THREE.TextureLoader().load( url, function ( texture )
		{
			// Sin esto la skin sale como una mancha borrosa en vez de pixeles
			texture.magFilter = THREE.NearestFilter;
			texture.minFilter = THREE.NearestFilter;
			texture.encoding = THREE.sRGBEncoding;
			textures[uuid] = texture;
		}, undefined, function ()
		{
			textures[uuid] = false;
		} );
		return null;
	}

	function forget( uuid )
	{
		var model = models[uuid];
		if( !model )
			return;
		if( model.marker )
		{
			model.marker.remove( model.group );
			if( model.marker.playerHeadElement )
				model.marker.playerHeadElement.style.display = "";
		}
		model.group.traverse( function ( node )
		{
			if( node.geometry )
				node.geometry.dispose();
		} );
		model.material.dispose();
		delete models[uuid];
	}

	function forgetAll()
	{
		for( var uuid in models )
			forget( uuid );
	}

	/**
	 * Reparte las seis caras de una caja por el sitio que le toca en la skin.
	 *
	 * <p>Es el desdoblado de siempre de Minecraft: a partir de la esquina (u,v) de
	 * la parte, cada cara cae en su rectangulo. Un pixel de desvio y el jugador
	 * lleva la cara en la nuca.</p>
	 */
	function applySkinUvs( geometry, u, v, w, h, d, textureWidth, textureHeight )
	{
		// El orden de las caras en una caja de three.js: +X, -X, +Y, -Y, +Z, -Z.
		// El muñeco mira hacia +Z, asi que su derecha cae en -X
		var faces = [
			[ u, v + d, d, h ],                     // +X: costado
			[ u + w + d, v + d, d, h ],             // -X: el otro costado
			[ u + d, v, w, d ],                     // +Y: arriba
			[ u + w + d, v, w, d ],                 // -Y: abajo
			[ u + d, v + d, w, h ],                 // +Z: delante
			[ u + w + 2 * d, v + d, w, h ]          // -Z: detras
		];

		var uv = geometry.attributes.uv;
		for( var face = 0; face < faces.length; face++ )
		{
			var box = faces[face];
			var left = box[0] / textureWidth;
			var right = (box[0] + box[2]) / textureWidth;
			// La imagen empieza arriba y las coordenadas de la escena abajo
			var top = 1 - box[1] / textureHeight;
			var bottom = 1 - (box[1] + box[3]) / textureHeight;

			var at = face * 4;
			uv.setXY( at, left, top );
			uv.setXY( at + 1, right, top );
			uv.setXY( at + 2, left, bottom );
			uv.setXY( at + 3, right, bottom );
		}
		uv.needsUpdate = true;
	}
})();
