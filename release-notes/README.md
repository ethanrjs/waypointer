# Release notes

Every release tag needs handwritten notes committed at:

`release-notes/<tag>.md`

For example, before tagging `v1.8.4`, write `release-notes/v1.8.4.md`, commit it with the release changes, and then push the tag:

```shell
git tag -a v1.8.4 -m "Release v1.8.4"
git push origin main
git push origin v1.8.4
```

The release workflow stops if the matching notes file is missing or empty. It builds and attests both distributable JARs from the tagged commit, verifies their SHA-256 hashes, and uses the handwritten file as the GitHub release description.
