# CastleCore 1.1.0 — Paper 26.2

A server-side castle-war plugin with dynamic teams, coloured banner cores, one-time team joining, team elimination, and match controls.

## Requirements
- Paper 26.2
- Java 25
- Gradle

## Build
Run from this project directory:

```bash
gradle build
```

The JAR will be created at `build/libs/CastleCore-1.1.0.jar`. Put it in the server's `plugins/` directory and restart the server.

## 1. Register each castle core (before starting a match)

Look at a solid block where the banner should stand, then run:

```text
/core set red red
/core set blue blue
/core set green green
/core set yellow yellow
/core set purple purple
/core set orange orange
/core set pink pink
/core set cyan cyan
/core set white white
```

The plugin places the selected banner one block above the block you're looking at. Team names are arbitrary; the colour must be one of Minecraft's 16 banner dye colours.

## 2. Start a match

```text
/core start
```

This restores all registered banner blocks, clears old team assignments and eliminations, and opens team joining.

## 3. Players join exactly once

```text
/core join red
```

A player can only join one team during the active match. They cannot switch teams or use `/core leave`. Their assignment persists if they disconnect and reconnect.

Players who have not joined a team cannot break cores.

## 4. Core destruction

When a player breaks an enemy core:
- The break is allowed.
- The team is eliminated for this match.
- Everyone sees an announcement naming the attacker and the destroyed team's banner.
- The eliminated team's core is removed from the active objective list.
- Players assigned to the eliminated team cannot destroy further cores.

The last team with a registered core wins automatically. If all cores are destroyed, the match ends with no winner.

## 5. End or reset

```text
/core end
```

Ends the match and clears all player team assignments. It leaves any surviving banners in place.

```text
/core reset
```

Ends the match, clears assignments and eliminations, and restores every registered banner at its saved location. Use this to prepare the next match.

## Other commands

- `/core status` — show match status, active cores, eliminated teams and assigned players.
- `/core list` — list active cores.
- `/core remove <team>` — unregister a core while no match is running.

## Permissions

- `castlecore.use` — default: everyone.
- `castlecore.admin` — default: operators; required for setup and match controls.

## Notes
- Team assignments are stored by player UUID, so reconnecting does not let a player switch teams.
- Match state and team assignments are saved to `plugins/CastleCore/config.yml`.
- This version implements core destruction and team elimination, but does not manage player death/respawn locations, kits, or teleportation to castles.
- Test on a staging server and back up your world before installing on a live server.


## Team castle respawn areas (v1.2.0)

Set each team's spawn while the match is stopped. Stand at the desired spawn point
inside the castle and run:

```text
/core setspawn red
/core setspawn blue
```

Players assigned to a team respawn at a safe position selected from the 3x3
horizontal block area centered on that saved location. No bed is required.
The spawn location is saved across server restarts. Use `/core spawn <team>`
to view the saved location. Team spawns can only be changed while the match is
not running. If a team is eliminated, its members no longer respawn at that
team's castle.


## Build automatically with GitHub Actions

1. Create a new GitHub repository and upload the contents of this project folder.
2. Open the repository's **Actions** tab.
3. Select **Build CastleCore** and click **Run workflow** (or push a commit to start it automatically).
4. When the workflow finishes successfully, open its run and download the `CastleCore-1.2.0` artifact.
5. Extract the downloaded artifact and place the `.jar` in your Paper server's `plugins` folder.
6. Restart the server and check the console for `CastleCore enabled`.

The workflow uses Java 25 and Gradle 8.14. The Paper API snapshot configured in
`build.gradle.kts` must be available from the Paper Maven repository for the build
to succeed. This workflow does not itself certify compatibility with every
Minecraft/Paper build.
