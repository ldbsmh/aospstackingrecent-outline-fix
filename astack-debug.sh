#!/system/bin/sh
# astack-debug.sh — AOSPStackingRecent 诊断脚本
#
# 用法（需要 root）：
#   su -c "sh /data/local/tmp/astack-debug.sh"
#   su -c "sh /data/local/tmp/astack-debug.sh collect   # 只抓已有日志，不重启桌面"
#   su -c "sh /data/local/tmp/astack-debug.sh kill      # 只杀桌面"
#   su -c "sh /data/local/tmp/astack-debug.sh watch      # 持续跟随输出（调试用）
#
# 默认行为：清空日志 -> 杀桌面 -> 开始记录 -> 提示你操作 -> 抓取结果

PKG="com.google.android.apps.nexuslauncher"
TAG="AStack"
OUT="/sdcard/astack-debug"
MODE="${1:-all}"

mkdir -p "$OUT" 2>/dev/null

case "$MODE" in
  kill)
    echo "[*] 正在杀掉桌面：$PKG"
    PID=$(pidof "$PKG")
    if [ -n "$PID" ]; then
      # 优先 am force-stop，它会触发 Launcher 的完整重启与模块重新挂钩
      am force-stop "$PKG" 2>/dev/null
      sleep 1
      PID2=$(pidof "$PKG")
      if [ -n "$PID2" ]; then
        echo "[*] force-stop 未生效，直接 kill -9 $PID2"
        kill -9 "$PID2" 2>/dev/null
      fi
      echo "[+] 桌面已终止，按 Home 键（或向上滑动）重新拉起"
    else
      echo "[!] 桌面没在运行"
    fi
    exit 0
    ;;

  watch)
    echo "[*] 实时跟随 $TAG 日志（Ctrl-C 退出）"
    logcat -s "$TAG:I" "*:S"
    exit 0
    ;;

  collect)
    STAMP=$(date +%Y%m%d-%H%M%S)
    FILE="$OUT/astack-$STAMP.log"
    echo "[*] 抓取现有日志 -> $FILE"
    logcat -d -v time -s "$TAG:I" "*:S" > "$FILE" 2>/dev/null
    BYTES=$(stat -c %s "$FILE" 2>/dev/null)
    LINES=$(awk "END{print NR}" "$FILE" 2>/dev/null)
    echo "[+] 完成：$LINES 行 / $BYTES 字节"
    if [ "$LINES" -eq 0 ]; then
      echo "[!] 没有内容。可能是：模块没启用 / 桌面重启后没进过多任务 / tag 不对"
    else
      echo "---- 前 5 行 ----"
      head -5 "$FILE"
      echo "---- 后 5 行 ----"
      tail -5 "$FILE"
    fi
    echo "[+] 文件路径：$FILE"
    exit 0
    ;;
esac

# ============ 完整流程 ============
STAMP=$(date +%Y%m%d-%H%M%S)
FILE="$OUT/astack-$STAMP.log"

echo "=============================================="
echo " AOSPStackingRecent 诊断"
echo "=============================================="

echo "[1/5] 检查模块是否已安装并启用"
if pm list packages 2>/dev/null | grep -q "aospstackingrecent"; then
  echo "    [+] 模块已安装：$(pm list packages | grep aospstackingrecent | head -1)"
else
  echo "    [!] 没找到 aospstackingrecent 包，检查是否装的是别的名字"
  pm list packages 2>/dev/null | grep -i stack
fi

echo "[2/5] 清空旧日志"
logcat -c 2>/dev/null
logcat -b main -c 2>/dev/null
echo "    [+] 已清空"

echo "[3/5] 杀掉桌面，让模块重新挂钩"
PID=$(pidof "$PKG")
echo "    当前 PID: ${PID:-<未运行>}"
am force-stop "$PKG" 2>/dev/null
sleep 1
PID2=$(pidof "$PKG")
if [ -n "$PID2" ]; then
  echo "    force-stop 未生效，kill -9 $PID2"
  kill -9 "$PID2" 2>/dev/null
  sleep 1
fi
echo "    [+] 桌面已终止"

echo "[4/5] 开始后台记录日志 -> $FILE"
# -v time 带时间戳；不后台跑 logcat -f，直接让它写出文件
logcat -v time -s "$TAG:I" "*:S" > "$FILE" 2>/dev/null &
LOGPID=$!
echo "    [+] 记录中 (pid $LOGPID)"

cat <<'TIP'

==============================================
  现在请手动操作（30~60 秒内完成）：

  1. 按 Home 键，等桌面起来
  2. 打开两三个应用
  3. 上滑进入多任务界面
  4. 左右滑动卡片 —— 重点复现轮廓错位
  5. 反复滑动几次，再来回切换一下
  6. 完成后回到这里按 Enter

==============================================
TIP

read -r _ 2>/dev/null || sleep 60

echo "[5/5] 停止记录"
kill "$LOGPID" 2>/dev/null
sleep 1

LINES=$(awk "END{print NR}" "$FILE" 2>/dev/null)
BYTES=$(stat -c %s "$FILE" 2>/dev/null)
echo "    [+] 共 $LINES 行 / $BYTES 字节"

if [ "${LINES:-0}" -eq 0 ]; then
  echo ""
  echo "!!!! 没有抓到任何日志 !!!!"
  echo "排查："
  echo "  1. 模块是否在 LSPosed 里启用了？作用域是否勾了 $PKG ?"
  echo "  2. 改完模块后重启过手机吗？"
  echo "  3. 用 'su -c \"sh $0 watch\"' 实时看，操作时观察有没有输出"
  echo "  4. 兜底：'logcat -d | grep -i stacking' 看模块自己有没有报错"
  logcat -d 2>/dev/null | grep -i "stacking\|AOSPStacking" | tail -20
else
  echo ""
  echo "========== 关键片段预览 =========="
  echo "--- TREE 快照（错位时的层级）---"
  grep -A40 "==== TREE ====" "$FILE" | tail -45
  echo ""
  echo "--- 偏移与平移量 ---"
  grep "applyTX" "$FILE" | tail -8
  echo ""
  echo "--- RecentsView 子层 ---"
  grep "RecentsView kids" "$FILE" | tail -4
fi

echo ""
echo "=============================================="
echo " 日志文件：$FILE"
echo " 把它拉到电脑或直接读："
echo "   adb pull $FILE"
echo "=============================================="
