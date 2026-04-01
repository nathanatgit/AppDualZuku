# AppDualZuku
A multi‑instance app manager powered by **Shizuku**.

## Overview
AppDualZuku is a lightweight utility that uses Shizuku privileges to manage Android workspaces (Managed Profiles).

It allows you to create isolated or shared environments for apps, enabling multiple parallel installations of the same application without heavy virtualization or root access.

## ⚠️ Important Warning

Always back up your files before performing any workspace‑related operations. Creating or removing managed workspaces involves Android’s multi‑user system; unexpected behavior from OEM restrictions may lead to data loss or even bootloop.
**Proceed only if you understand the risks.**

## ⚠️ 重要提醒
在进行任何与工作空间相关的操作之前，请务必备份。创建或删除工作空间涉及 Android 多用户系统，某些 OEM 限制可能导致数据丢失风险甚至设备启动问题。**请在充分了解风险并完成备份后再继续。**

## 🚀 Getting Started
1. **Shizuku:** Ensure the [Shizuku](https://shizuku.rikka.app/) service is running and authorized.
   
   (**Note:** In some device, you need to allow Shizuku full permission of auto-start/chained wake-up/mutual wake-up, or battery usage unlimited.)
   
2. **Permissions:** Grant the app Shizuku permissions when prompted.
   
3. **Management:** Use the **Settings** tab to create a new profile (Managed/Clone) if not exist. Or you can editing packages in an existing workspace.

    (**Note:** Managed space is an isolated environment from main user while cloned space using shared storage. Cloned space may not work in all devices.)

## 📸 Screenshots
<img src="https://github.com/user-attachments/assets/47bbcf08-15c2-4b4c-9f6c-b4eec25f3929" width="400" alt="Screenshot_20260401_171810">
<img src="https://github.com/user-attachments/assets/3ee8d246-3adf-49c2-8d5a-ed8bf7c4b8f0" width="400" alt="Screenshot_20260401_171816">

## ⚖️ License
This project is licensed under the GNU General Public License v3.0 (GPLv3).
You may copy, distribute, and modify the software as long as modifications are documented and remain under GPLv3.
For the full license text, please search for the GNU GPL v3.0.
