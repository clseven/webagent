"""
Build clean Markdown lessons from local learn-claude-code repo.
Reads README.md + code.py, writes to docs/lessons/.

Usage: python scripts/build_lessons.py C:/path/to/learn-claude-code
"""

import sys
from pathlib import Path

LESSONS = [
    ("s01_agent_loop",      "s01", "工具与执行", "🔵"),
    ("s02_tool_use",        "s02", "工具与执行", "🔵"),
    ("s03_permission",      "s03", "工具与执行", "🔵"),
    ("s04_hooks",           "s04", "工具与执行", "🔵"),
    ("s05_todo_write",      "s05", "规划与协调", "🟢"),
    ("s06_subagent",        "s06", "规划与协调", "🟢"),
    ("s07_skill_loading",   "s07", "规划与协调", "🟢"),
    ("s08_context_compact", "s08", "记忆管理",   "🟣"),
    ("s09_memory",          "s09", "记忆管理",   "🟣"),
    ("s10_system_prompt",   "s10", "规划与协调", "🟢"),
    ("s11_error_recovery",  "s11", "规划与协调", "🟢"),
    ("s12_task_system",     "s12", "多Agent平台", "🔴"),
    ("s13_background_tasks","s13", "并发",       "🟠"),
    ("s14_cron_scheduler",  "s14", "并发",       "🟠"),
    ("s15_agent_teams",     "s15", "多Agent平台", "🔴"),
    ("s16_team_protocols",  "s16", "多Agent平台", "🔴"),
    ("s17_autonomous_agents","s17", "多Agent平台", "🔴"),
    ("s18_worktree_isolation","s18","多Agent平台", "🔴"),
    ("s19_mcp_plugin",      "s19", "多Agent平台", "🔴"),
    ("s20_comprehensive",   "s20", "多Agent平台", "🔴"),
]


def extract_metadata(readme: str, seq: str) -> dict:
    lines = readme.split("\n")
    title = ""
    sub_quote = ""
    harness = ""
    for line in lines:
        if line.startswith("# ") and not title:
            raw = line[2:].strip()
            if raw.lower().startswith(seq.lower()):
                raw = raw[len(seq):].strip(": -— ").strip()
            title = raw
        elif line.startswith("> *") and not sub_quote:
            sub_quote = line.strip().lstrip("> ").strip("*").strip()
        elif line.startswith(">") and "Harness" in line and not harness:
            harness = line.strip().lstrip("> ").strip("**").strip()
    return {"title": title, "sub_quote": sub_quote, "harness": harness}


def build_lesson(src_dir: Path, dir_name: str, seq: str, category: str, emoji: str) -> str:
    readme_path = src_dir / dir_name / "README.md"
    code_path = src_dir / dir_name / "code.py"

    readme = readme_path.read_text(encoding="utf-8") if readme_path.exists() else ""
    code = code_path.read_text(encoding="utf-8") if code_path.exists() else ""

    if not readme:
        return f"# {seq}: (no README)\n"

    meta = extract_metadata(readme, seq)
    loc = len(code.split("\n")) if code else 0
    title = meta["title"] or dir_name

    # Clean README body
    lines = readme.split("\n")
    body_lines = []
    past_h1 = False
    for line in lines:
        if "[English]" in line and "README.en.md" in line:
            continue
        if line.startswith("# ") and not past_h1:
            past_h1 = True
            continue
        body_lines.append(line)
    body = "\n".join(body_lines).strip()

    out = []
    out.append(f"# {seq} {title}")
    out.append("")
    out.append(f"> **模块**: {emoji} {category}  |  **源码**: `{dir_name}/code.py` ({loc} 行)")
    if meta["sub_quote"]:
        out.append(f"> {meta['sub_quote']}")
    if meta["harness"]:
        out.append(f"> {meta['harness']}")
    out.append("")
    out.append(body)

    if code:
        out.append(f"\n\n---\n\n## 📎 完整源码 `{dir_name}/code.py` ({loc} 行)\n")
        out.append("```python")
        out.append(code)
        out.append("```")

    return "\n".join(out) + "\n"


def build_index(src_dir: Path) -> str:
    out = []
    out.append("# Learn Claude Code — 完整课程索引")
    out.append("")
    out.append("> 从 0 到 1 构建 nano Claude Code-like agent，每次只加一个机制")
    out.append("")
    out.append("---")
    out.append("")

    last_cat = ""
    for dir_name, seq, cat, emoji in LESSONS:
        if cat != last_cat:
            last_cat = cat
            out.append(f"### {emoji} {cat}")
            out.append("")
            out.append("| 编号 | 课程 | 文件 |")
            out.append("|------|------|------|")
        rp = src_dir / dir_name / "README.md"
        readme = rp.read_text(encoding="utf-8") if rp.exists() else ""
        meta = extract_metadata(readme, seq) if readme else {}
        title = meta.get("title") or dir_name
        out.append(f"| {seq} | [{title}]({seq}.md) | `{dir_name}/` |")

    out.append("")
    out.append("---")
    out.append("")
    out.append("## 学习路径")
    out.append("")
    out.append("```")
    out.append("s01 Agent Loop     ─┐")
    out.append("s02 Tool Use       ─┤ 工具与执行")
    out.append("s03 Permission     ─┤")
    out.append("s04 Hooks          ─┘")
    out.append("")
    out.append("s05 TodoWrite      ─┐")
    out.append("s06 Subagent       ─┤")
    out.append("s07 Skill Loading  ─┤ 规划与协调")
    out.append("s10 System Prompt  ─┤")
    out.append("s11 Error Recovery ─┘")
    out.append("")
    out.append("s08 Context Compact─┐ 记忆管理")
    out.append("s09 Memory         ─┘")
    out.append("")
    out.append("s13 Background      ─┐ 并发")
    out.append("s14 Cron Scheduler ─┘")
    out.append("")
    out.append("s12 Task System     ─┐")
    out.append("s15 Agent Teams     ─┤")
    out.append("s16 Team Protocols  ─┤")
    out.append("s17 Autonomous      ─┤ 多Agent平台")
    out.append("s18 Worktree        ─┤")
    out.append("s19 MCP Tools       ─┤")
    out.append("s20 Comprehensive   ─┘")
    out.append("```")

    return "\n".join(out) + "\n"


def main():
    src = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    out_dir = Path(__file__).parent.parent / "docs" / "lessons"
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"Source: {src}")
    print(f"Output: {out_dir}")
    print()

    for dir_name, seq, cat, emoji in LESSONS:
        path = out_dir / f"{seq}.md"
        print(f"  {seq} {dir_name}...", end=" ", flush=True)
        try:
            md = build_lesson(src, dir_name, seq, cat, emoji)
            path.write_text(md, encoding="utf-8")
            print(f"OK ({len(md)//1024}KB)")
        except Exception as e:
            print(f"FAIL: {e}")

    idx_path = out_dir / "INDEX.md"
    idx_path.write_text(build_index(src), encoding="utf-8")
    print(f"  INDEX.md OK")
    print(f"\nDone! {len(LESSONS)} lessons saved.")


if __name__ == "__main__":
    main()
