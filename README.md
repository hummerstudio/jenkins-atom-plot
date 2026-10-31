# jenkins-atom-plot

把 CSV 序列画成一张自包含的 SVG。

plot 插件在 master 上用 JFreeChart 画 PNG。原子只有文件，而有用的产物——构建页上能看的那张图——
本质只是几何。写 SVG 的好处是零依赖、可读、可 diff：**坐标能被断言**，图才可信。

- 逗号或制表符分隔，第一行是表头
- `xColumn` 给列名或序号（默认第 0 列），`yColumns` 留空则画除 X 外所有列
- 多个文件时图例带文件名前缀
- `skipNonNumeric=false` 时遇到非数字行**报错并指出行号列名**，而不是偷偷跳过

smoke 不只看"文件生成了"：它解析 SVG 里的 `polyline` 点坐标，核对首尾点、轴刻度与图例文字。

```bash
./build.sh && ./tools/smoke.sh
```
