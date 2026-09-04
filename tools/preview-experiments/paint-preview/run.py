#!/usr/bin/env python3
"""Compare painter CPU raster/native-image work without creating GPU textures."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classpath", type=Path, required=True, help="file containing the test runtime classpath")
parser.add_argument("--java-home", type=Path, required=True, help="Java 25 installation directory")
parser.add_argument("--baseline", default="1ea7d08", help="revision with the original painter raster")
parser.add_argument("--output", type=Path, help="directory for generated source, classes, timing and PNGs")
args = parser.parse_args()
repo = Path(__file__).resolve().parents[3]
output = args.output.resolve() if args.output else Path(tempfile.mkdtemp(prefix="waypointer-paint-preview-"))
output.mkdir(parents=True, exist_ok=True)
classes = output / "classes"
classes.mkdir(exist_ok=True)
classpath = args.classpath.read_text().strip()
source_path = "src/client/java/com/babbur/waypointer/screen/WaypointPaintPreviewTexture.java"
baseline = subprocess.run(
    ["git", "show", f"{args.baseline}:{source_path}"], cwd=repo,
    check=True, text=True, capture_output=True,
).stdout
baseline = baseline.replace("WaypointPaintPreviewTexture", "BaselineNativePaintPreview")
# Keep the baseline raster algorithm unchanged. Replace only GPU ownership/upload
# with a NativeImage so both sides measure real native writes without a render device.
baseline = baseline.replace(
    "    private final Identifier id;\n    private final DynamicTexture texture;",
    "    private final NativeImage image = new NativeImage(SIZE, SIZE, false);",
)
start = baseline.index("    BaselineNativePaintPreview()")
end = baseline.index("    void update(", start)
baseline = baseline[:start] + "    NativeImage image() { return image; }\n\n" + baseline[end:]
baseline = baseline.replace("Minecraft.getInstance().getTextureManager().release(id);", "image.close();")
baseline = baseline.replace("        NativeImage image = texture.getPixels();\n        if (image == null) return;\n", "")
baseline = baseline.replace("        texture.upload();\n", "")
baseline_path = output / "BaselineNativePaintPreview.java"
baseline_path.write_text(baseline)
subprocess.run([
    str(args.java_home / "bin/javac"), "-cp", classpath, "-d", str(classes),
    str(repo / "src/main/java/com/babbur/waypointer/core/WaypointPaint.java"),
    str(repo / source_path), str(baseline_path),
    str(Path(__file__).with_name("NativeRasterBenchmark.java")),
], cwd=repo, check=True)
result = subprocess.run([
    str(args.java_home / "bin/java"), "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
    "-cp", str(classes) + os.pathsep + classpath,
    "com.babbur.waypointer.screen.NativeRasterBenchmark", str(output),
], cwd=repo, check=True, text=True, capture_output=True)
(output / "timing.txt").write_text(result.stdout)
print(result.stdout, end="")
print(f"Saved timing, sample PNGs and generated baseline source to {output}")
