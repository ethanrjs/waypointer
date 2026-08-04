# Release notes

Every release tag needs handwritten notes committed at:

`release-notes/<tag>.md`

For example, before tagging `v1.8.5`, write `release-notes/v1.8.5.md`, commit it with the release changes, and then push the tag:

```shell
git tag -a v1.8.5 -m "Release v1.8.5"
git push origin main
git push origin v1.8.5
```

The release workflow stops if the matching notes file is missing or empty. It builds and attests all three distributable JARs, verifies their metadata and SHA-256 hashes, and uses the handwritten file as the GitHub release description. The workflow pins the Minecraft 1.21.11 source commit; update that pin after the 1.21.11 release branch is ready and before tagging `main`. Run the workflow manually to validate the combined release artifact without publishing it.
