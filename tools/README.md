# tools

| 脚本 | 作用 |
|---|---|
| `generate_app_icons.py` | 从 `design/app-icon-source.png` 生成 Android 启动图标（legacy 方形/圆形、adaptive 前景层、anydpi-v26 描述） |
| `test_generate_app_icons.py` | 图标管线的单元测试（尺寸、透明度、adaptive 引用） |

运行需要 Pillow（Python 3.10+）：

```powershell
python tools/generate_app_icons.py
python -m unittest discover -s tools -p "test_*.py"
```

生成后由 `app/src/test/java/com/dshm/bot/AppIconResourcesTest.kt` 在构建期继续校验资源接线。
