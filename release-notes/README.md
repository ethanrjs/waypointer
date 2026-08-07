# Release notes

Every release tag needs handwritten notes committed at:

`release-notes/<tag>.md`

For example, before tagging `v1.8.5`, write `release-notes/v1.8.5.md`, commit it with the release changes, and then push the tag:

```shell
git tag -a v1.8.5 -m "Release v1.8.5"
git push origin main
git push origin v1.8.5
```

The release workflow stops if the matching notes file is missing or empty. It builds and attests both distributable JARs (Minecraft 26.1.2 and 26.2), verifies their metadata and SHA-256 hashes, and uses the handwritten file as the GitHub release description. Run the workflow manually to validate the release artifact without publishing it.

Minecraft 1.21.11 is no longer part of the release. `v1.8.6` was the last tag to ship a 1.21.11 JAR; the workflow built it from a pinned commit on the `1.21.11` branch. To resume publishing it, restore the pinned-checkout and Java 21 build steps and raise the expected JAR count back to three.
