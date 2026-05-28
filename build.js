import esbuild from 'esbuild';

async function build() {
  console.log('Starting esbuild bundle...');
  try {
    await esbuild.build({
      entryPoints: ['src/server.js'],
      bundle: true,
      platform: 'node',
      format: 'cjs',
      outfile: 'dist/server.bundle.js',
      external: ['sqlite3'], // sqlite3 is a native binary module, must remain external
      define: {
        // Replace import.meta.url with a virtual file URL matching the pkg snapshot location
        'import.meta.url': '"file:///C:/snapshot/dist/server.bundle.js"'
      }
    });
    console.log('esbuild bundle completed successfully: dist/server.bundle.js');
  } catch (err) {
    console.error('esbuild bundle failed:', err);
    process.exit(1);
  }
}

build();
