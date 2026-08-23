/*
 * Comprueba el guion del muñeco 3D sin navegador.
 *
 * Lo que se mira es a que trozo de la skin va a parar cada cara. Es lo que
 * diria una foto —"¿tiene la cara en la cara o en la nuca?"— pero medido, que no
 * depende de que alguien mire bien la imagen.
 *
 * Se le pone al guion DE VERDAD un three.js y un visor de mentira: se ejecuta el
 * fichero que se instala, no una copia de su logica.
 */
"use strict";

const fs = require( "fs" );
const path = require( "path" );
const assert = require( "assert" );

const SCRIPT = path.join( __dirname, "..", "..", "src", "resources", "webapp", "endershare-players3d.js" );

// ---- three.js de mentira, lo justo para lo que usa el guion -----------------

function BufferAttribute( count )
{
	this.count = count;
	this.array = new Float32Array( count * 2 );
	this.setXY = function ( index, x, y )
	{
		this.array[index * 2] = x;
		this.array[index * 2 + 1] = y;
	};
	this.getXY = function ( index )
	{
		return [ this.array[index * 2], this.array[index * 2 + 1] ];
	};
}

class Object3D
{
	constructor()
	{
		this.children = [];
		this.position = { x: 0, y: 0, z: 0, set: ( x, y, z ) => { this.position.x = x; this.position.y = y; this.position.z = z; } };
		this.rotation = { x: 0, y: 0, z: 0 };
	}
	add( child ) { this.children.push( child ); return this; }
	remove( child ) { this.children = this.children.filter( c => c !== child ); }
	traverse( fn ) { fn( this ); this.children.forEach( c => c.traverse && c.traverse( fn ) ); }
}

class BoxGeometry
{
	constructor( width, height, depth )
	{
		this.parameters = { width, height, depth };
		// Una caja son 6 caras de 4 vertices
		this.attributes = { uv: new BufferAttribute( 24 ) };
	}
	dispose() {}
}

const loaded = [];

global.window = {
	THREE: {
		Object3D,
		BoxGeometry,
		Mesh: class Mesh extends Object3D
		{
			constructor( geometry, material ) { super(); this.geometry = geometry; this.material = material; }
		},
		MeshBasicMaterial: class { constructor( options ) { Object.assign( this, options ); } dispose() {} },
		TextureLoader: class
		{
			load( url, onLoad )
			{
				loaded.push( url );
				// Skin moderna de 64x64
				onLoad( { image: { width: 64, height: 64 } } );
			}
		},
		NearestFilter: "nearest",
		sRGBEncoding: "srgb"
	},
	setTimeout: setTimeout
};
global.setTimeout = setTimeout;
global.console = console;

// ---- visor de mentira ------------------------------------------------------

const UUID = "abc-123";
const marker = new Object3D();
marker.playerHeadElement = { style: {} };

class FakeSet
{
	constructor() { this.data = { playerheadsUrl: "maps/overworld/assets/playerheads/" }; }
	updateFromPlayerData() { return true; }
	getPlayerMarker( uuid ) { return uuid === UUID ? marker : null; }
}

const set = new FakeSet();
window.THREE = global.window.THREE;
window.bluemap = { playerMarkerManager: { getPlayerMarkerSet: () => set } };
global.THREE = window.THREE;

// ---- ejecutar el guion de verdad -------------------------------------------

const vm = require( "vm" );
const source = fs.readFileSync( SCRIPT, "utf8" );
vm.runInNewContext( source, { window, console, setTimeout }, { filename: SCRIPT } );

function feed( yaw )
{
	set.updateFromPlayerData( { players: [ { uuid: UUID, name: "Prueba",
		position: { x: 0, y: 0, z: 0 }, rotation: { pitch: 0, yaw: yaw, roll: 0 } } ] } );
}

// La primera vuelta pide la textura; la segunda ya monta el muñeco
feed( 0 );
feed( 0 );

// ---- comprobaciones --------------------------------------------------------

assert.strictEqual( loaded.length, 1, "Tiene que pedir la skin entera una sola vez" );
assert.strictEqual( loaded[0], "maps/overworld/assets/playerskins/abc-123.png",
	"Pide la skin donde no es: " + loaded[0] );

const group = marker.children.find( c => c.children.length >= 6 );
assert.ok( group, "No se ha montado el muñeco" );
assert.strictEqual( group.children.length, 6, "El muñeco son 6 cajas" );
assert.strictEqual( group.position.y, -1.8, "La chincheta va a los ojos: el muñeco se cuelga por los pies" );
assert.strictEqual( marker.playerHeadElement.style.display, "none", "La chincheta plana tiene que esconderse" );

// Un pixel de la skin son 1/16 de bloque: la cabeza mide 8 pixeles
const head = group.children[0];
assert.ok( Math.abs( head.geometry.parameters.width - 0.5 ) < 1e-9, "La cabeza mide medio bloque" );
assert.ok( Math.abs( head.position.y - 28 / 16 ) < 1e-9, "La cabeza va sobre el cuerpo" );

/** Que rectangulo de la skin (en pixeles, con el 0,0 arriba) usa una cara. */
function regionOf( geometry, face )
{
	const uv = geometry.attributes.uv;
	const at = face * 4;
	const [ left, top ] = uv.getXY( at );
	const [ right, bottom ] = uv.getXY( at + 3 );
	return [ Math.round( left * 64 ), Math.round( (1 - top) * 64 ),
		Math.round( right * 64 ), Math.round( (1 - bottom) * 64 ) ];
}

// Orden de las caras de una caja: +X, -X, +Y, -Y, +Z, -Z. El muñeco mira a +Z,
// asi que la cara de la persona tiene que caer donde Mojang pone la cara
assert.deepStrictEqual( regionOf( head.geometry, 4 ), [ 8, 8, 16, 16 ],
	"La cara no esta en la cara: " + regionOf( head.geometry, 4 ) );
assert.deepStrictEqual( regionOf( head.geometry, 5 ), [ 24, 8, 32, 16 ], "La nuca no esta en su sitio" );
assert.deepStrictEqual( regionOf( head.geometry, 2 ), [ 8, 0, 16, 8 ], "La coronilla no esta en su sitio" );
assert.deepStrictEqual( regionOf( head.geometry, 0 ), [ 0, 8, 8, 16 ], "Un costado no esta en su sitio" );
assert.deepStrictEqual( regionOf( head.geometry, 1 ), [ 16, 8, 24, 16 ], "El otro costado no esta en su sitio" );

const body = group.children[1];
assert.deepStrictEqual( regionOf( body.geometry, 4 ), [ 20, 20, 28, 32 ],
	"El pecho no esta en su sitio: " + regionOf( body.geometry, 4 ) );

// El yaw de Minecraft cuenta al reves que el giro de la escena
feed( 90 );
assert.ok( Math.abs( group.rotation.y + Math.PI / 2 ) < 1e-9,
	"Mira al lado contrario: " + group.rotation.y );

// Al desconectarse alguien, su muñeco se va y la chincheta vuelve
set.updateFromPlayerData( { players: [] } );
assert.strictEqual( marker.children.length, 0, "El muñeco se queda clavado al desconectarse" );
assert.strictEqual( marker.playerHeadElement.style.display, "", "La chincheta no vuelve" );

console.log( "muñeco 3D: todo correcto (" + group.children.length + " partes, UVs en su sitio)" );
