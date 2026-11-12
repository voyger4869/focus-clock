@echo off
REM 构建 Windows 桌面版（NSIS 安装包 + portable 便携版）
REM 需要先安装 Node.js 和 npm

cd /d "%~dp0"
echo [1/2] 安装依赖...
call npm install
if errorlevel 1 goto :error

echo [2/2] 打包...
call npm run dist
if errorlevel 1 goto :error

echo.
echo 打包完成！产物在 dist\ 目录
echo   - 专注时钟 Setup %VERSION%.exe   （安装包）
echo   - 专注时钟 %VERSION%.exe          （便携版）
goto :end

:error
echo.
echo 构建失败，请检查上方错误信息。
exit /b 1

:end
