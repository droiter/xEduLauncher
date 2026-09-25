#!/bin/bash
# 把产物续传上 120（那条链路上传慢，scp 一次传不完就被超时打断；这里按已传字节数续）
# 用法：upload_120.sh <本地文件> [远端文件名]
set -u
F=$1
NAME=${2:-$(basename "$F")}
R=/var/www/html/$NAME
TOTAL=$(stat -c%s "$F")
LAST=$TOTAL
for i in $(seq 1 12); do
  HAVE=$(ssh -o BatchMode=yes -o ConnectTimeout=10 yacc@192.168.1.120 "stat -c%s $R 2>/dev/null || echo 0")
  if [ "$HAVE" -ge "$TOTAL" ]; then break; fi
  echo "[$i] 已有 $HAVE / $TOTAL，续传"
  timeout 540 bash -c "tail -c +$((HAVE+1)) '$F' | ssh -o BatchMode=yes yacc@192.168.1.120 'cat >> $R'"
  echo "[$i] 这一轮结束，远端现在 $(ssh -o BatchMode=yes yacc@192.168.1.120 "stat -c%s $R")"
  LAST=$HAVE
done
echo "本地 md5 ：$(md5sum "$F" | cut -d' ' -f1)"
echo "远端 md5 ：$(ssh -o BatchMode=yes yacc@192.168.1.120 "md5sum $R")"
