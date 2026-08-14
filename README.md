# AppDual

A multi‑instance app manager powered by **Shizuku** or **root**.

## Overview

AppDual is a lightweight utility that uses Shizuku or root privileges to manage Android workspaces (Managed Profiles).

It allows you to create isolated or shared environments for apps, enabling multiple parallel installations of the same application without heavy virtualization.

## ⚠️ Important Warning

1. Always back up your files before performing any workspace‑related operations. Creating or removing managed workspaces involves Android’s multi‑user system; unexpected behavior from OEM restrictions may lead to data loss or even bootloop.
   **Proceed only if you understand the risks.**

2. Some of the custom rom may limit the number of workspace and clonespace corresponding to the use profile. This may prevent this app creating workspaces. In this case, if you have root access with xposed, [AlwaysCreateUser](https://github.com/icepony/AlwaysCreateUser) is recomended to bypass the limits.

## ⚠️ 重要提醒

1. 在进行任何与工作空间相关的操作之前，请务必备份。创建或删除工作空间涉及 Android 多用户系统，某些 OEM 限制可能导致数据丢失风险甚至设备启动问题。**请在充分了解风险并完成备份后再继续。**

2. 部分定制 ROM 可能会根据所用用户配置文件限制工作空间和克隆空间的数量，从而导致本应用无法创建工作空间。此时，如果您拥有 root 权限并安装了 Xposed，建议使用 [AlwaysCreateUser](https://github.com/icepony/AlwaysCreateUser) 来绕过此限制。

## 🚀 Getting Started

1. **Choose an execution mode:** AppDual can run `pm`/`am` commands either through [Shizuku](https://shizuku.rikka.app/) or directly through **root** (`su`) — toggle this in the **Settings** tab under "Execution Mode". Root, if detected, bypasses Shizuku/ADB entirely.

   (**Note:** In some devices, you need to allow Shizuku full permission of auto-start/chained wake-up/mutual wake-up, or battery usage unlimited.)
2. **Permissions:** Grant the app Shizuku permissions when prompted (not needed if using root).
3. **Management:** Use the **Settings** tab to create a new profile (Managed/Clone) if not exist, or you can edit packages in an existing workspace.

   (**Note:** Managed space is an isolated environment from main user while cloned space using shared storage. Cloned space may not work in all devices.)
4. **Batch management:** Long‑press any app (or tap "Batch Manage" in the app list) to enter selection mode, pick apps, then tap the floating button to install/uninstall/clone them across workspaces, or export/import the selection as a list.

## 📸 Screenshots

<img src="screenshots/01_app_list.png" width="260" alt="App list">
<img src="screenshots/02_settings.png" width="260" alt="Settings: execution mode and workspaces">
<img src="screenshots/03_batch_select.png" width="260" alt="Batch selection mode">
<img src="screenshots/04_batch_actions.png" width="260" alt="Batch actions: install, uninstall, clone, export, import">

## ⚖️ License

This project is licensed under the GNU General Public License v3.0 (GPLv3).
You may copy, distribute, and modify the software as long as modifications are documented and remain under GPLv3.
For the full license text, please search for the GNU GPL v3.0.
