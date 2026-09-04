"""Run encoder-portfolio experiments against already compiled production classes.

The generated sparse encoder is an isolated temporary copy of the current source.
Its only change is offering a canonical quotient coordinate body alongside Rice.
No repository source or classes are changed; production's decoder verifies output.
"""
import argparse
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('corpus', type=pathlib.Path)
    parser.add_argument('--classpath-file', type=pathlib.Path, required=True,
                        help='File containing the current test runtime classpath')
    parser.add_argument('--java-home', type=pathlib.Path, required=True,
                        help='Java 25 JDK home containing bin/java and bin/javac')
    parser.add_argument('--common-compact', action='store_true',
                        help='Compare the former general-only COMMON path with an eligible full compact candidate')
    args = parser.parse_args()
    classpath = args.classpath_file.read_text().strip()
    if args.common_compact:
        with tempfile.TemporaryDirectory(prefix='waypointer-common-compact-') as temp:
            classes = pathlib.Path(temp) / 'classes'
            source = pathlib.Path(__file__).with_name('CompactProjectionExperiment.java')
            subprocess.run([str(args.java_home / 'bin/javac'), '-cp', classpath,
                            '-d', str(classes), str(source)], check=True)
            subprocess.run([str(args.java_home / 'bin/java'), '-Xmx1g', '-cp',
                            str(classes) + ':' + classpath,
                            'com.babbur.waypointer.codec.CompactProjectionExperiment',
                            str(args.corpus)], check=True)
        return
    original = (ROOT / 'src/main/java/com/babbur/waypointer/codec/V10SparseRouteCodec.java').read_text()
    source = original.replace('V10SparseRouteCodec', 'SparseQuotientExperiment')
    old = '''            byte[] coordinateBody = V10BareRouteCodec.encodeCoordinateBody(
                    V10BareRouteCodec.coordinatesOf(group), coordinateMode);
'''
    new = '''            int[][] sourceCoordinates = V10BareRouteCodec.coordinatesOf(group);
            byte[] defaultBody = V10BareRouteCodec.encodeCoordinateBody(sourceCoordinates, coordinateMode);
            List<byte[]> bodies = new ArrayList<>();
            bodies.add(defaultBody);
            if (coordinateMode == V10Transport.MODE_DIRECT && sourceCoordinates.length > 1
                    && sourceCoordinates.length <= V10BareEntropyCodec.MAX_QUOTIENT_WAYPOINTS) {
                byte[] quotient = V10BareEntropyCodec.encodeQuotient(sourceCoordinates);
                bodies.add(Arrays.copyOfRange(quotient, 1, quotient.length));
            }
            for (byte[] coordinateBody : bodies) {
'''
    end = '        if (best == null) throw new IllegalStateException'
    if source.count(old) != 1 or source.count(end) != 1:
        raise SystemExit('Sparse codec changed; review this experiment before adapting its copy.')
    source = source.replace(old, new).replace(end, '        }\n' + end)
    with tempfile.TemporaryDirectory(prefix='waypointer-codec-experiment-') as temp:
        directory = pathlib.Path(temp)
        sparse = directory / 'SparseQuotientExperiment.java'
        sparse.write_text(source)
        classes = directory / 'classes'
        subprocess.run([str(args.java_home / 'bin/javac'), '-cp', classpath,
                        '-d', str(classes), str(sparse),
                        str(pathlib.Path(__file__).with_name('PortfolioExperiment.java'))], check=True)
        subprocess.run([str(args.java_home / 'bin/java'), '-Xmx1g', '-cp',
                        str(classes) + ':' + classpath,
                        'com.babbur.waypointer.codec.PortfolioExperiment', str(args.corpus)], check=True)


if __name__ == '__main__':
    main()
