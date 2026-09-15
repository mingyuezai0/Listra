// 把媒体播放器仓库根目录的 index.html 复制到 www/
const fs = require('fs');
const path = require('path');

const SRC = path.join(__dirname, '..', '..', 'index.html');
const DST = path.join(__dirname, '..', 'www', 'index.html');

fs.copyFileSync(SRC, DST);
console.log('copied:', path.relative(process.cwd(), DST), fs.statSync(DST).size, 'bytes');
