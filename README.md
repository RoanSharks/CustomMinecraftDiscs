# CustomMusicDiscs (Paper 1.21.x)

Adds:

- `/customdisc create <name> <url.ogg> <color>`
- `/customdisc pack`

The create command now:

- Colored display name
- Downloads the OGG file
- Stores it in a generated resource pack at `plugins/CustomMusicDiscs/generated-pack`
- Rebuilds `plugins/CustomMusicDiscs/pack.zip`
- Stores the generated sound key in item metadata

When a custom disc is used on a jukebox:

- The disc is inserted into the jukebox
- Its generated custom sound (`custommusicdiscs:<sound_id>`) is played
- The player sees a “Now playing” action bar with the URL
- The player also gets a clickable chat link to the source URL

## Resource Pack Delivery

- The plugin can host `pack.zip` from a tiny built-in HTTP server.
- `/customdisc pack` sends the generated pack to the player.
- Set `resource-pack.public-url` in `config.yml` to your publicly reachable URL.
- If `resource-pack.public-url` is blank, the plugin falls back to `http://127.0.0.1:<port>/pack.zip`.

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
- Minecraft clients cannot directly stream arbitrary external URLs from a plugin.
- This plugin works around that by downloading the OGG, adding it to a generated pack, and playing it as a pack sound.
