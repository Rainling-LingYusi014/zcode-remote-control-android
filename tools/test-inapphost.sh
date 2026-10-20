#!/usr/bin/env bash
# inAppHost() 白名单绕过矩阵回归测试：在桌面 JVM 上运行真实编译产物（无需设备）。
# 前提：先跑过一次 ./build.sh（存在 build-local/classes）。用法：bash tools/test-inapphost.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
AJ="$(find "$HERE/../toolchain" -maxdepth 3 -name android.jar 2>/dev/null | sed -n '1p')"
[ -n "$AJ" ] || AJ="$(find /e/.ZCode/toolchain -maxdepth 3 -name android.jar | sed -n '1p')"
T="$HERE/build-local/test"
mkdir -p "$T/com/dsha/zremote" "$HERE/build-local/test-classes"
cat > "$T/com/dsha/zremote/TestHost.java" << 'EOF'
package com.dsha.zremote;

/** inAppHost 绕过矩阵（默认包无法访问包内方法，须同包）。期望值=false 的都是绕过尝试。 */
public class TestHost {
    static int fails = 0;
    static void check(String host, boolean expect) {
        boolean got;
        try { got = PanelController.inAppHost(host); } catch (Exception e) { got = false; }
        String tag = host == null ? "null" : host;
        if (got != expect) { fails++; System.out.println("FAIL  [" + tag + "] expect=" + expect + " got=" + got); }
        else System.out.println("ok    [" + tag + "] -> " + got);
    }
    public static void main(String[] args) {
        check("zcode.z.ai", true); check("chat.z.ai", true); check("a.b.c.z.ai", true);
        check("z.ai", true); check("bigmodel.cn", true); check("open.bigmodel.cn", true);
        check("z.ai.evil.com", false); check("evilz.ai", false); check("notz.ai", false);
        check("z.ai.com", false); check("evil.com", false); check("evil.z.ai.", false);
        check(null, false); check("", false); check(".z.ai", false); check(".bigmodel.cn", false);
        System.out.println(fails == 0 ? "ALL_PASS" : "FAILURES=" + fails);
        if (fails > 0) System.exit(1);
    }
}
EOF
CP="$(cygpath -m "$AJ");$(cygpath -m "$HERE/build-local/classes");$(cygpath -m "$HERE/build-local/test-classes")"
javac -encoding UTF-8 -cp "$CP" -d "$HERE/build-local/test-classes" "$T/com/dsha/zremote/TestHost.java"
java -cp "$CP" com.dsha.zremote.TestHost
