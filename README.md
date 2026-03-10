# CustomMusicDiscs (Paper 1.21.x)

Adds:

- `/customdisc <name> <url.ogg> <color>`

The command gives the player a custom music disc item with:

- Colored display name
- OGG URL stored in item metadata (PersistentDataContainer)
- Chosen color stored in item metadata

## Build

From this folder:

```bash
mvn clean package
```

Jar output:

- `target/custommusicdiscs-1.0.0.jar`

## Install

1. Copy the jar into your server `plugins` folder.
2. Start/restart the Paper server.

## Notes

- URL must be `http` or `https` and end with `.ogg`.
- Color accepts named Adventure colors like `red`, `gold`, `aqua`, etc., or hex like `#FF55AA`.
- This plugin creates and tags custom disc items; it does not stream or auto-play URL audio by itself.
