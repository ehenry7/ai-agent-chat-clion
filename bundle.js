const fs = require('fs');
const path = require('path');

const ROOT_DIR = process.argv[2] || '.';
const MAX_FILE_SIZE_BYTES = 50 * 1024; // Individual file skip limit (50KB)
const MAX_BUNDLE_SIZE_BYTES = 40 * 1024; // Target split size for markdown parts (40KB)

const EXCLUDED_DIRS = ['build', '.gradle', '.idea', '.git', 'node_modules', 'out'];
const INCLUDED_EXTENSIONS = ['.kt', '.java', '.kts', '.xml', '.properties', '.json'];

function shouldProcess(filePath) {
    const ext = path.extname(filePath);
    return INCLUDED_EXTENSIONS.includes(ext);
}

function walk(dir, fileList = []) {
    const files = fs.readdirSync(dir);
    files.forEach(file => {
        const filePath = path.join(dir, file);
        const stat = fs.statSync(filePath);
        if (stat.isDirectory()) {
            if (!EXCLUDED_DIRS.includes(file)) {
                walk(filePath, fileList);
            }
        } else {
            if (shouldProcess(filePath) && stat.size <= MAX_FILE_SIZE_BYTES) {
                fileList.push(filePath);
            }
        }
    });
    return fileList;
}

function generateBundle() {
    const files = walk(ROOT_DIR);
    let partIndex = 1;
    let currentSize = 0;
    let outputFileName = `project-bundle-part${partIndex}.md`;
    let stream = fs.createWriteStream(outputFileName, 'utf8');

    console.log(`Writing to ${outputFileName}...`);
    let header = `# CLion Plugin Project Bundle - Part ${partIndex}\n\n`;
    stream.write(header);
    currentSize += Buffer.byteLength(header, 'utf8');

    files.forEach(file => {
        const relativePath = path.relative(ROOT_DIR, file);
        const content = fs.readFileSync(file, 'utf8');
        const ext = path.extname(file).replace('.', '');
        const lang = ext === 'kts' ? 'kotlin' : ext;

        let fileSnippet = `**File: \`${relativePath}\`**\n\n`;
        fileSnippet += `\`\`\`${lang}\n${content}\n\`\`\`\n\n---\n\n`;

        const snippetSize = Buffer.byteLength(fileSnippet, 'utf8');

        if (currentSize + snippetSize > MAX_BUNDLE_SIZE_BYTES) {
            stream.end();
            console.log(`Part ${partIndex} complete.`);

            partIndex++;
            outputFileName = `project-bundle-part${partIndex}.md`;
            console.log(`Writing to ${outputFileName}...`);

            stream = fs.createWriteStream(outputFileName, 'utf8');
            currentSize = 0;

            header = `# CLion Plugin Project Bundle - Part ${partIndex}\n\n`;
            stream.write(header);
            currentSize += Buffer.byteLength(header, 'utf8');
        }

        stream.write(fileSnippet);
        currentSize += snippetSize;
    });

    stream.end();
    console.log(`Bundle generation complete. Total parts created: ${partIndex}`);
}

generateBundle();
