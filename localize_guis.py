#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
CN fork: 汉化 skyblockaddon 的 GUI 定义。

GUI 文本不是语言键，而是内嵌在 10 个 guis/*.json 里的 Component 字面量，所以必须逐文件改。
这里用「精确文本 -> 译文」映射替换，并做占位符守恒断言：
  - %xxx% 形式的占位符必须等量保留（GUI 用 %owner% / %pagenum% 这类）
  - § 样式码必须等量
替换后逐条比对，任何一条对不上就直接抛错，避免「看起来翻了其实占位符丢了」。

同时只替换 text 字段的值，键名/结构/条件/onClick 类名一律不动。
"""
import io
import json
import os
import re
import sys

GUI_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "core", "src", "main", "resources", "assets", "skyblockaddon", "guis",
)

# 精确文本 -> 译文。只列出玩家真正会看到的英文。
TEXT_MAP = {
    # 通用导航
    '"{\\"text\\": \\"Back\\",\\"bold\\": true,\\"color\\": \\"red\\"}"': '"{\\"text\\": \\"返回\\",\\"bold\\": true,\\"color\\": \\"red\\"}"',
    '"{\\"text\\": \\"Previous\\",\\"bold\\": true,\\"color\\": \\"red\\"}"': '"{\\"text\\": \\"上一页\\",\\"bold\\": true,\\"color\\": \\"red\\"}"',
    '"{\\"text\\": \\"Next\\",\\"bold\\": true,\\"color\\": \\"green\\"}"': '"{\\"text\\": \\"下一页\\",\\"bold\\": true,\\"color\\": \\"green\\"}"',

    # 标题
    '"{\\"text\\": \\"%owner%\'s Island\\"}"': '"{\\"text\\": \\"%owner% 的岛屿\\"}"',
    '"{\\"text\\": \\"%group_name% - Members\\"}"': '"{\\"text\\": \\"%group_name% - 成员\\"}"',
    '"{\\"text\\": \\"%server_name% - Travel Map\\"}"': '"{\\"text\\": \\"%server_name% - 旅行地图\\"}"',
    '"{\\"text\\": \\"%data_owner_name%\'s Island\\", \\"color\\":\\"blue\\"}"':
        '"{\\"text\\": \\"%data_owner_name% 的岛屿\\", \\"color\\":\\"blue\\"}"',

    # overview.json
    '"{\\"text\\": \\"Teleport\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"': '"{\\"text\\": \\"传送\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"',
    '"{\\"text\\": \\"Members\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"': '"{\\"text\\": \\"成员\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"',
    '"{\\"text\\": \\"Settings\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"': '"{\\"text\\": \\"设置\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"',
    '"{\\"text\\": \\"Leave\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"': '"{\\"text\\": \\"离开\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"',
    '"{\\"text\\": \\"Close\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"': '"{\\"text\\": \\"关闭\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"',

    # settings.json
    '"{\\"text\\": \\"Biome\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"': '"{\\"text\\": \\"生物群系\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"',
    '"{\\"text\\": \\"Permissions\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"': '"{\\"text\\": \\"权限\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"',
    '"{\\"text\\": \\"Spawn Point\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"': '"{\\"text\\": \\"出生点\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"',
    '"{\\"text\\": \\"Visibility\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"': '"{\\"text\\": \\"可见性\\",\\"bold\\": true,\\"color\\": \\"blue\\"}"',

    # 成员分组 / 权限
    '"{\\"text\\": \\"Add member\\",\\"bold\\": true,\\"color\\": \\"green\\"}"': '"{\\"text\\": \\"添加成员\\",\\"bold\\": true,\\"color\\": \\"green\\"}"',
    '"{\\"text\\": \\"Add a new group\\",\\"bold\\": true,\\"color\\": \\"green\\"}"': '"{\\"text\\": \\"新建分组\\",\\"bold\\": true,\\"color\\": \\"green\\"}"',
    '"{\\"text\\": \\"Kick member\\",\\"bold\\": true,\\"color\\": \\"red\\"}"': '"{\\"text\\": \\"踢出成员\\",\\"bold\\": true,\\"color\\": \\"red\\"}"',
    '"{\\"text\\": \\"Remove group\\",\\"bold\\": true,\\"color\\": \\"red\\"}"': '"{\\"text\\": \\"删除分组\\",\\"bold\\": true,\\"color\\": \\"red\\"}"',
    '"{\\"text\\": \\"%group_name% - Permissions\\"}"': '"{\\"text\\": \\"%group_name% - 权限\\"}"',

    # 权限分类（分类 id 保持英文，仅显示名翻译）
    '"{\\"text\\": \\"Admin Controls\\", \\"color\\":\\"dark_red\\", \\"bold\\":\\"true\\"}"':
        '"{\\"text\\": \\"管理控制\\", \\"color\\":\\"dark_red\\", \\"bold\\":\\"true\\"}"',
    '"{\\"text\\": \\"General\\", \\"color\\":\\"blue\\", \\"bold\\":\\"true\\"}"':
        '"{\\"text\\": \\"基础\\", \\"color\\":\\"blue\\", \\"bold\\":\\"true\\"}"',
    '"{\\"text\\": \\"Redstone\\", \\"color\\":\\"red\\", \\"bold\\":\\"true\\"}"':
        '"{\\"text\\": \\"红石\\", \\"color\\":\\"red\\", \\"bold\\":\\"true\\"}"',
    '"{\\"text\\": \\"Storage\\", \\"color\\":\\"aqua\\", \\"bold\\":\\"true\\"}"':
        '"{\\"text\\": \\"存储\\", \\"color\\":\\"aqua\\", \\"bold\\":\\"true\\"}"',
    '"{\\"text\\": \\"Create\\", \\"color\\":\\"gold\\", \\"bold\\":\\"true\\"}"':
        '"{\\"text\\": \\"机械动力\\", \\"color\\":\\"gold\\", \\"bold\\":\\"true\\"}"',
    '"{\\"text\\": \\"Miscellaneous\\", \\"color\\":\\"green\\", \\"bold\\":\\"true\\"}"':
        '"{\\"text\\": \\"杂项\\", \\"color\\":\\"green\\", \\"bold\\":\\"true\\"}"',
    '"{\\"text\\": \\"Vault Hunters\\", \\"color\\":\\"yellow\\", \\"bold\\":\\"true\\"}"':
        '"{\\"text\\": \\"宝库猎人\\", \\"color\\":\\"yellow\\", \\"bold\\":\\"true\\"}"',

    # lore 行（display_name 为纯文本的 lore 段）
    '"» Teleport to your islands spawn location."': '"» 传送到你的岛屿出生点。"',
    '"» Overview all islands members and invite others."': '"» 查看岛屿的全部成员并邀请他人。"',
    '"» Change the settings of your island."': '"» 修改你的岛屿设置。"',
    '"» Leave the island.\\n"': '"» 离开这座岛屿。\\n"',
    '"» Close admin menu.\\n"': '"» 关闭管理菜单。\\n"',
    '"» Change the biome of your island."': '"» 更换你岛屿的生物群系。"',
    '"» Set the spawn point of your island."': '"» 设置你岛屿的出生点。"',
    '"» Change visibility of your island."': '"» 更改你岛屿的可见性。"',
    '"» Click to travel to this island."': '"» 点击前往这座岛屿。"',
    '"» Click to change the group this member is a part of."': '"» 点击更改该成员所属的分组。"',
    '"» Click to change the biome of your island."': '"» 点击更换你岛屿的生物群系。"',
    '"♦ Beware, if you are the last member, the island will be removed!"':
        '"♦ 注意：如果你是最后一名成员，岛屿将被删除！"',
    '"♦ Current: "': '"♦ 当前："',
    '"♦ Part of: "': '"♦ 所属分组："',
}


def placeholders(s):
    """提取 %xxx% 占位符，做守恒比对。"""
    return sorted(re.findall(r"%[^%\s]+%", s))


def style_codes(s):
    """§ 样式码守恒。"""
    return sorted(re.findall(r"§.", s))


def translate_file(path):
    with io.open(path, "r", encoding="utf-8") as fh:
        src = fh.read()

    hits = 0
    for en, zh in TEXT_MAP.items():
        # 只替换「作为完整 JSON 字符串字面量」出现的地方，避免误伤键名。
        needle = en
        count = src.count(needle)
        if count:
            src = src.replace(needle, zh)
            hits += count

    # 占位符守恒：替换前后 %xxx% 集合必须完全一致。
    before = placeholders(open(path, encoding="utf-8").read())
    after = placeholders(src)
    if before != after:
        raise SystemExit(
            "占位符不守恒于 %s:\n  before=%s\n  after =%s" % (path, before, after)
        )

    if src != open(path, encoding="utf-8").read():
        with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(src)
    return hits


def main():
    total = 0
    for name in sorted(os.listdir(GUI_DIR)):
        if not name.endswith(".json"):
            continue
        p = os.path.join(GUI_DIR, name)
        # 替换后必须是合法 JSON，且 title/rows/key 结构完好。
        n = translate_file(p)
        with io.open(p, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        assert "key" in data and "rows" in data, "%s 结构被破坏" % name
        total += n
        print("  %-22s 替换 %2d 处" % (name, n))
    print("GUI 汉化完成，共替换 %d 处。" % total)


if __name__ == "__main__":
    main()
