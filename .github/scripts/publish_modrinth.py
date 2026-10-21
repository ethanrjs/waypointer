import argparse
import functools
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path

API = "https://api.modrinth.com/v2/"
USER_AGENT = "ethanrjs/waypointer release mirror (github.com/ethanrjs/waypointer)"
PROJECT = "VsST7Yka"
BUILT_IN = {"minecraft", "java", "fabricloader", "fabric"}


def call(path, body=None, headers=None, method="GET"):
    request = urllib.request.Request(API + path, data=body, method=method,
                                     headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Modrinth {method} {path} failed with {error.code}: "
                         f"{error.read().decode(errors='replace')}")


def numbers(version):
    core = re.split(r"[-+]", version, maxsplit=1)[0]
    return [int(part) if part.isdigit() else part for part in core.split(".")]


def compare(a, b):
    x, y = numbers(a), numbers(b)
    width = max(len(x), len(y))
    x, y = x + [0] * (width - len(x)), y + [0] * (width - len(y))
    for left, right in zip(x, y):
        if left in ("x", "X", "*") or right in ("x", "X", "*"):
            return 0
        if isinstance(left, str) or isinstance(right, str):
            left, right = str(left), str(right)
        if left != right:
            return -1 if left < right else 1
    return 0


def accepts(predicate, version):
    if isinstance(predicate, list):
        return any(accepts(item, version) for item in predicate)
    return all(term_accepts(term, version) for term in predicate.split())


def term_accepts(term, version):
    if term == "*":
        return True
    operator = next((op for op in (">=", "<=", ">", "<", "=", "~", "^") if term.startswith(op)), "")
    bound = term[len(operator):]
    order = compare(version, bound)
    if operator in ("", "="):
        return order == 0
    if operator == ">=":
        return order >= 0
    if operator == "<=":
        return order <= 0
    if operator == ">":
        return order > 0
    if operator == "<":
        return order < 0
    parts = [p for p in numbers(bound) if isinstance(p, int)]
    upper = [parts[0] + 1] if operator == "^" or len(parts) < 2 else [parts[0], parts[1] + 1]
    return order >= 0 and compare(version, ".".join(map(str, upper))) < 0


def game_versions(predicate):
    releases = [tag["version"] for tag in call("tag/game_version")
                if tag["version_type"] == "release" and accepts(predicate, tag["version"])]
    return sorted(releases, key=functools.cmp_to_key(compare))


def dependency(slug, predicate, games):
    project = call(f"project/{slug}")
    query = urllib.parse.urlencode({"loaders": json.dumps(["fabric"])})
    versions = sorted(call(f"project/{project['id']}/version?{query}"),
                      key=lambda v: v["date_published"], reverse=True)
    usable = [v for v in versions if accepts(predicate, v["version_number"])]
    chosen = next((v for v in usable if set(games) <= set(v["game_versions"])), None) \
        or next((v for v in usable if games[-1] in v["game_versions"]), None)
    pinned = {"version_id": chosen["id"]} if chosen else {}
    return {"project_id": project["id"], **pinned, "dependency_type": "required"}, \
        (chosen["version_number"] if chosen else "any version")


def channel(tag):
    lowered = tag.lower()
    if "alpha" in lowered:
        return "alpha"
    if "beta" in lowered or "-rc" in lowered:
        return "beta"
    return "release"


def multipart(data, jar):
    boundary = uuid.uuid4().hex
    head = f"--{boundary}\r\nContent-Disposition: form-data; name=\"data\"\r\n" \
           f"Content-Type: application/json\r\n\r\n"
    file_head = f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{jar.name}\"\r\n" \
                f"Content-Type: application/java-archive\r\n\r\n"
    body = head.encode() + json.dumps(data).encode() + b"\r\n" \
        + file_head.encode() + jar.read_bytes() + b"\r\n" + f"--{boundary}--\r\n".encode()
    return body, f"multipart/form-data; boundary={boundary}"


def main():
    parser = argparse.ArgumentParser(description="Mirror a Waypointer release's jars to Modrinth.")
    parser.add_argument("release_dir")
    parser.add_argument("tag")
    parser.add_argument("--notes")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    project = os.environ.get("MODRINTH_PROJECT", PROJECT)
    token = os.environ.get("MODRINTH_TOKEN", "")
    if not token and not args.dry_run:
        raise SystemExit("MODRINTH_TOKEN is not set")
    release_dir = Path(args.release_dir)
    version = args.tag.removeprefix("v")
    changelog = Path(args.notes or release_dir / "RELEASE_NOTES.md").read_text(encoding="utf-8")
    jars = sorted(release_dir.glob("*.jar"))
    if not jars:
        raise SystemExit(f"No jars in {release_dir}")
    published = {f["filename"] for v in call(f"project/{project}/version") for f in v["files"]}

    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            meta = json.loads(archive.read("fabric.mod.json"))
        if meta.get("version") != version:
            raise SystemExit(f"{jar.name} is version {meta.get('version')}, not {version}")
        depends = meta.get("depends", {})
        minecraft = depends.get("minecraft")
        if not minecraft or minecraft == "*":
            raise SystemExit(f"{jar.name} does not declare a Minecraft range")
        games = game_versions(minecraft)
        if not games:
            raise SystemExit(f"{jar.name}: no Minecraft release on Modrinth matches {minecraft}")
        dependencies, pins = [], []
        for slug, predicate in depends.items():
            if slug not in BUILT_IN:
                entry, pinned = dependency(slug, predicate, games)
                dependencies.append(entry)
                pins.append(f"{slug} {pinned}")
        data = {
            "name": f"Waypointer {version}",
            "version_number": version,
            "changelog": changelog,
            "dependencies": dependencies,
            "game_versions": games,
            "version_type": channel(args.tag),
            "loaders": ["fabric"],
            "featured": False,
            "status": "listed",
            "project_id": project,
            "file_parts": ["file"],
            "primary_file": "file",
        }
        print(f"{jar.name}: Minecraft {', '.join(games)}; requires {', '.join(pins)}")
        if jar.name in published:
            print("  already on Modrinth, skipped")
            continue
        if args.dry_run:
            print("  dry run, not uploaded")
            continue
        body, content_type = multipart(data, jar)
        created = call("version", body, {"Authorization": token, "Content-Type": content_type}, "POST")
        print(f"  published https://modrinth.com/mod/{project}/version/{created['id']}")


if __name__ == "__main__":
    main()
