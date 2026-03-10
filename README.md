# GL46 Core

Replaces legacy fixed-function OpenGL with core profile equivalents for Minecraft 1.12.2 (via Cleanroom/Forge). Enables OpenGL 4.6 core profile rendering by intercepting and redirecting deprecated GL calls to modern alternatives.

## Features

- **Core profile matrix stack** — Software replacement for `glMatrixMode`/`glPushMatrix`/`glPopMatrix`/`glLoadIdentity`/`glTranslatef`/`glRotatef`/`glScalef`/`glMultMatrixf`/`glOrtho`/`glFrustum`
- **Core profile state tracking** — Software tracking of fog, lighting, color, and alpha test parameters removed in core profile
- **Legacy GL redirects** — ASM-based redirection of `glBegin`/`glEnd` immediate mode, `glLight`, `glFog`, `glAlphaFunc`, `glColor`, `glNormal`, `glTexCoord`, `glVertexPointer`, etc.
- **Core shader program** — UBO-based shader with lighting, fog, texture, and color support replacing the fixed-function pipeline
- **Display list emulation** — Captures legacy display list calls and replays them through the core profile pipeline
- **GL debug output** — Optional synchronous debug output with stack traces for diagnosing GL errors

## Requirements

- Minecraft 1.12.2 with [Cleanroom](https://github.com/CleanroomMC/Cleanroom) (provides LWJGL 3 / OpenGL 4.6 context)
- GPU with OpenGL 4.5+ support

## License

This project is licensed under the [GNU Lesser General Public License v3.0](https://www.gnu.org/licenses/lgpl-3.0.html) (LGPL-3.0).

See [LICENSE](LICENSE) for the full license text.

## Credits

- Display list and FFP shader generation inspired by [Mesa](https://gitlab.freedesktop.org/mesa/mesa) (MIT license)
