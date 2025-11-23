@echo off
REM 启动脚本，设置必要的 JVM 参数以修复 log4j 问题
REM 这些参数必须在 JAR 启动时设置，无法在代码中设置
REM 注意：如果直接运行 JAR 仍然出错，请使用此脚本启动
java -Dlog4j2.isThreadContextMapInheritable=true -Dlog4j2.disable.jmx=true -Dlog4j2.formatMsgNoLookups=true -Dlog4j2.callerClass=project.Launcher -Dlog4j2.enable.threadlocals=false -Dlog4j2.enable.direct.encoders=false -jar build\libs\LowYSwampHutForFixedSeed-1.0.jar
pause

