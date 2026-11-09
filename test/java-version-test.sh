#!/bin/bash
# Exercise the real scripts with harmless fake JDK commands in a temporary tree.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/jdk/bin"
cat > "$WORK/jdk/bin/java" <<'JAVA'
#!/bin/bash
if [ "${1:-}" = -version ]; then
  echo "openjdk version \"$FAKE_JAVA_VERSION\"" >&2
fi
exit 0
JAVA
printf '#!/bin/bash\nexit 0\n' > "$WORK/jdk/bin/javac"
cp "$WORK/jdk/bin/javac" "$WORK/jdk/bin/jar"
chmod +x "$WORK/jdk/bin/"*
for side in client server dbclient; do
  mkdir -p "$WORK/$side/script" "$WORK/$side/lib"
  cp "$ROOT/$side/script/compile.sh" "$ROOT/$side/script/start-$side.sh" "$WORK/$side/script/"
  for script in compile "start-$side"; do
    for version in 1.8.0_504 8-ea 21.0.9 21+35 26-ea; do
      JAVA_HOME="$WORK/jdk" FAKE_JAVA_VERSION="$version" "$WORK/$side/script/$script.sh" > "$WORK/output" 2>&1
    done
    for version in 1.7.0 7-ea nonsense; do
      if JAVA_HOME="$WORK/jdk" FAKE_JAVA_VERSION="$version" "$WORK/$side/script/$script.sh" > "$WORK/output" 2>&1; then
        echo "FAIL: $side/$script accepted $version"; exit 1
      fi
    done
  done
done
echo "Java version scripts: 48 cases passed"
