# A multi‑instance app manager powered by Shizuku

## Overview

This lightweight Android tool uses Shizuku privileges to execute adb‑level commands safely and conveniently.
It allows you to create multiple isolated app workspaces, enabling parallel installations of the same application without cloning frameworks or heavy virtualization.

## ⚠️ Important Warning / 重要提醒

Always back up your files before performing any workspace‑related operations.

Creating or removing managed workspaces involves Android’s multi‑user system, and unexpected behavior from OEM restrictions or system bugs may lead to data loss for apps installed in those workspaces.

**Proceed only if you understand the risks and have backed up important data.**

在进行任何与工作空间相关的操作之前，请务必备份你的重要文件。

创建或删除工作空间会涉及 Android 多用户系统。

由于不同厂商的系统限制或潜在的系统异常，工作空间中的应用数据可能会出现丢失风险。

**请在充分了解风险并完成数据备份后再继续操作。**

## Features
- 🔧 Shizuku‑powered elevated operations
- 📦 Create multiple managed workspaces (not just dual apps anymore)
- 🖥️ Install and manage multiple instances of the same app
- 🪄 Simple GUI workflow — no manual adb commands required
- 🚀 lightweight, and no root needed

## Requirements
- Shizuku service running and authorized
- Android device with multi‑user support
- Basic understanding of adb concepts (optional, but helpful)

## How It Works
The app creates additional Android user profiles (workspaces) and installs selected apps into them.
Each workspace behaves like an isolated environment, allowing multiple independent instances of the same app.

## Limitations
- Some OEM‑restricted devices may limit multi‑user functionality
- More advanced workspace controls may be added in future updates

## Screenshots
![Screenshot_2026-01-20-23-43-48-095_com nathanhanapps appdualzuku](https://github.com/user-attachments/assets/9ce69d11-6b64-44f8-966a-d45c5271c846)

## License
This project is licensed under the GNU General Public License v3.0 (GPLv3).
You may copy, distribute, and modify the software as long as modifications are documented and remain under GPLv3.
For the full license text, please search for the GNU GPL v3.0.


