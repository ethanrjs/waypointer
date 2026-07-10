# Third-Party Notices

## Skyblocker dungeon coordinate algorithms

Parts of `DungeonMapMath` and the dungeon `Direction` model are adapted from
Skyblocker's dungeon room mapping and coordinate-transform algorithms.

- Project: https://github.com/SkyblockerMod/Skyblocker
- Source files:
  - `src/main/java/de/hysky/skyblocker/skyblock/dungeon/secrets/Room.java`
  - `src/main/java/de/hysky/skyblocker/skyblock/dungeon/DungeonMapUtils.java`
- License: GNU Lesser General Public License v3.0
- License text: https://github.com/SkyblockerMod/Skyblocker/blob/master/LICENSE

The adapted portions remain available under the GNU Lesser General Public
License v3.0. Waypointer's changes include representing coordinates with Java
primitive arrays, adding explicit bounds and failure handling, and separating
map-grid detection from room-local waypoint projection. The complete modified
source is distributed in this repository.

## Odin room detection data and algorithm

Waypointer bundles converted Catacombs room core hashes and per-room
metadata (secret, crypt, and trapped-chest counts) from Odin and ports
Odin-compatible dungeon room grid/core detection behavior for named dungeon
room detection.

- Project: https://github.com/odtheking/Odin
- Room data source commit: `2f96b4481d223287567c8e38efa5bc1ae0a5787d`
- Room data source file: `src/main/resources/assets/odin/rooms.json`
- Algorithm source commit: `57c4fa5d7d92a67bda440aedcb45010dafae89c7`
- Algorithm source file: `src/main/kotlin/com/odtheking/odin/utils/skyblock/dungeon/ScanUtils.kt`
- License: BSD 3-Clause License

BSD 3-Clause License

Copyright (c) 2025, odtheking

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
