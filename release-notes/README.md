# Release notes

Every release tag needs handwritten notes committed at:

`release-notes/<tag>.md`

For example, before tagging `v1.8.5`, write `release-notes/v1.8.5.md`, commit it with the release changes, and then push the tag:

```shell
git tag -a v1.8.5 -m "Release v1.8.5"
git push origin main
git push origin v1.8.5
```
