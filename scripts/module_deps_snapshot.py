#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

SETTINGS_FILE = "settings.gradle.kts"

INCLUDE_RE = re.compile(
    r'^\s*include\(\s*"(?P<module>:[^"]+)"\s*\)\s*$',
    flags=re.MULTILINE,
)

PROJECT_DEP_RE = re.compile(
    r'^\s*(?P<config>[A-Za-z_][A-Za-z0-9_]*)\s*\(\s*project\(\s*"(?P<module>:[^"]+)"\s*\)\s*\)\s*$',
    flags=re.MULTILINE,
)

BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", flags=re.DOTALL)
LINE_COMMENT_RE = re.compile(r"^\s*//.*$", flags=re.MULTILINE)


def strip_comments(text: str) -> str:
    text = BLOCK_COMMENT_RE.sub("", text)
    # Only drop full-line comments to avoid corrupting strings that might contain `//`.
    return LINE_COMMENT_RE.sub("", text)


def module_to_dir(module: str) -> Path:
    parts = [part for part in module.split(":") if part]
    return Path(*parts)


def parse_modules(settings_path: Path) -> list[str]:
    settings_text = settings_path.read_text(encoding="utf-8")
    modules = [match.group("module") for match in INCLUDE_RE.finditer(settings_text)]

    # Keep the original order in settings.gradle.kts but de-duplicate.
    seen: set[str] = set()
    ordered: list[str] = []
    for module in modules:
        if module not in seen:
            seen.add(module)
            ordered.append(module)
    return ordered


def classify_config_scope(config: str) -> str:
    lower = config.lower()
    if "androidtest" in lower or lower.startswith("androidtest"):
        return "test"
    if "test" in lower or lower.startswith("test"):
        return "test"
    return "main"


def parse_project_deps(build_file: Path) -> list[dict[str, str]]:
    if not build_file.exists():
        return []
    text = strip_comments(build_file.read_text(encoding="utf-8"))
    deps: list[dict[str, str]] = []
    for match in PROJECT_DEP_RE.finditer(text):
        config = match.group("config")
        module = match.group("module")
        deps.append(
            {
                "configuration": config,
                "module": module,
                "scope": classify_config_scope(config),
            }
        )
    return deps


def build_snapshot(repo_root: Path) -> dict[str, Any]:
    settings_path = repo_root / SETTINGS_FILE
    if not settings_path.exists():
        raise FileNotFoundError(f"{SETTINGS_FILE} not found under {repo_root}")

    modules = parse_modules(settings_path)
    snapshot_modules: list[dict[str, Any]] = []
    for module in modules:
        module_dir = module_to_dir(module)
        build_file = repo_root / module_dir / "build.gradle.kts"
        snapshot_modules.append(
            {
                "name": module,
                "dir": module_dir.as_posix(),
                "build_file": (module_dir / "build.gradle.kts").as_posix(),
                "project_dependencies": parse_project_deps(build_file),
            }
        )

    return {
        "settings_file": SETTINGS_FILE,
        "module_count": len(modules),
        "modules": snapshot_modules,
    }


def mermaid_id(module: str) -> str:
    # :repository:danmaku -> repository_danmaku
    raw = module.strip(":")
    normalized = re.sub(r"[^A-Za-z0-9_]", "_", raw)
    return normalized or "root"


def render_mermaid(snapshot: dict[str, Any], *, scope: str = "main") -> str:
    nodes: list[str] = []
    edges: set[tuple[str, str]] = set()

    for module in snapshot["modules"]:
        module_name = module["name"]
        node_id = mermaid_id(module_name)
        nodes.append(f'  {node_id}["{module_name}"]')

    for module in snapshot["modules"]:
        from_name = module["name"]
        from_id = mermaid_id(from_name)
        for dep in module["project_dependencies"]:
            if dep["scope"] != scope:
                continue
            to_name = dep["module"]
            to_id = mermaid_id(to_name)
            edges.add((from_id, to_id))

    nodes_sorted = "\n".join(sorted(nodes))
    edges_sorted = "\n".join(f"  {src} --> {dst}" for src, dst in sorted(edges))
    return "\n".join(["graph TD", nodes_sorted, "", edges_sorted]).strip() + "\n"


def render_markdown(snapshot: dict[str, Any]) -> str:
    total_edges = 0
    main_edges = 0
    test_edges = 0
    for module in snapshot["modules"]:
        for dep in module["project_dependencies"]:
            total_edges += 1
            if dep["scope"] == "main":
                main_edges += 1
            else:
                test_edges += 1

    lines: list[str] = []
    lines.append("# 模块直接依赖快照（Gradle `project(...)`）")
    lines.append("")
    lines.append("本文件由脚本自动生成：`python3 scripts/module_deps_snapshot.py --write`")
    lines.append("")
    lines.append("- 数据来源：`settings.gradle.kts` + 各模块 `build.gradle.kts`")
    lines.append("- 口径：仅统计 `dependencies { ... project(\":...\") ... }` 中的 **模块级直接依赖**（不包含外部库依赖）")
    lines.append("- 注意：静态解析无法覆盖 Gradle 条件分支/插件注入等场景；如出现争议以实际编译为准")
    lines.append("")
    lines.append("## 汇总")
    lines.append("")
    lines.append(f'- 模块数：{snapshot["module_count"]}')
    lines.append(f"- 依赖条目数：{total_edges}（生产 {main_edges} / 测试 {test_edges}）")
    lines.append("")
    lines.append("## 依赖列表（按模块）")
    lines.append("")

    for module in snapshot["modules"]:
        module_name = module["name"]
        deps: list[dict[str, str]] = module["project_dependencies"]

        main_deps: dict[str, set[str]] = {}
        test_deps: dict[str, set[str]] = {}
        for dep in deps:
            config = dep["configuration"]
            target = dep["module"]
            group = main_deps if dep["scope"] == "main" else test_deps
            group.setdefault(config, set()).add(target)

        lines.append(f"### {module_name}")
        lines.append("")

        lines.append("**生产依赖**")
        if not main_deps:
            lines.append("- （无）")
        else:
            for config in sorted(main_deps.keys()):
                targets = ", ".join(sorted(main_deps[config]))
                lines.append(f"- `{config}`：{targets}")
        lines.append("")

        lines.append("**测试依赖**")
        if not test_deps:
            lines.append("- （无）")
        else:
            for config in sorted(test_deps.keys()):
                targets = ", ".join(sorted(test_deps[config]))
                lines.append(f"- `{config}`：{targets}")
        lines.append("")

    lines.append("## Mermaid（生产依赖）")
    lines.append("")
    lines.append("```mermaid")
    lines.append(render_mermaid(snapshot, scope="main").rstrip())
    lines.append("```")
    lines.append("")

    return "\n".join(lines)


def write_outputs(repo_root: Path, snapshot: dict[str, Any], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    md_path = out_dir / "module_dependencies_snapshot.md"
    json_path = out_dir / "module_dependencies_snapshot.json"

    md_path.write_text(render_markdown(snapshot), encoding="utf-8")
    json_path.write_text(
        json.dumps(snapshot, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate a direct Gradle module dependency snapshot from settings.gradle.kts + build.gradle.kts."
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="Repository root directory (default: repo root inferred from script location).",
    )
    parser.add_argument(
        "--format",
        choices=["md", "json", "mermaid"],
        default="md",
        help="Output format when printing to stdout (default: md).",
    )
    parser.add_argument(
        "--write",
        action="store_true",
        help="Write snapshot files to document/architecture/ (md + json).",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("document/architecture"),
        help="Output directory when using --write (default: document/architecture).",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    repo_root: Path = args.repo_root.resolve()
    snapshot = build_snapshot(repo_root)

    if args.write:
        write_outputs(repo_root, snapshot, repo_root / args.out_dir)

    if args.format == "md":
        sys.stdout.write(render_markdown(snapshot))
    elif args.format == "json":
        sys.stdout.write(json.dumps(snapshot, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    else:
        sys.stdout.write(render_mermaid(snapshot, scope="main"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
