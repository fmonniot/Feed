
export async function instantiate(imports={}, runInitializer=true) {
    imports['_cachedJsObjects_'] = imports['_cachedJsObjects_'] ?? new WeakMap();
    const cachedJsObjects = imports['_cachedJsObjects_'];

    // ref must be non-null
    function getCachedJsObject(ref, ifNotCached) {
        if (typeof ref !== 'object' && typeof ref !== 'function') return ifNotCached;
        const cached = cachedJsObjects.get(ref);
        if (cached !== void 0) return cached;
        cachedJsObjects.set(ref, ifNotCached);
        return ifNotCached;
    }


    imports['@js-joda/core'] = imports['@js-joda/core'] ?? await import('@js-joda/core');

    const _ref_QGpzLWpvZGEvY29yZQ_ = imports['@js-joda/core'];

    const wasmJsTag = WebAssembly.JSTag;
    const wasmTag = wasmJsTag ?? new WebAssembly.Tag({ parameters: ['externref'] });

    const js_code = {
        'kotlin.createJsError' : (message, cause) => new Error(message, { cause }),
        'kotlin.wasm.internal.jsThrow' : wasmTag === wasmJsTag ? (e) => { throw e; } : () => {},
        'kotlin.wasm.internal.stringLength' : (x) => x.length,
        'kotlin.wasm.internal.jsExportStringToWasm' : (src, srcOffset, srcLength, dstAddr) => { 
            const mem16 = new Uint16Array(wasmExports.memory.buffer, dstAddr, srcLength);
            let arrayIndex = 0;
            let srcIndex = srcOffset;
            while (arrayIndex < srcLength) {
                mem16.set([src.charCodeAt(srcIndex)], arrayIndex);
                srcIndex++;
                arrayIndex++;
            }     
             },
        'kotlin.wasm.internal.importStringFromWasm' : (address, length, prefix) => { 
            const mem16 = new Uint16Array(wasmExports.memory.buffer, address, length);
            const str = String.fromCharCode.apply(null, mem16);
            return (prefix == null) ? str : prefix + str;
             },
        'kotlin.wasm.internal.getJsEmptyString' : () => '',
        'kotlin.wasm.internal.externrefToString' : (ref) => String(ref),
        'kotlin.wasm.internal.externrefEquals' : (lhs, rhs) => lhs === rhs,
        'kotlin.wasm.internal.externrefHashCode' : 
        (() => {
        const dataView = new DataView(new ArrayBuffer(8));
        function numberHashCode(obj) {
            if ((obj | 0) === obj) {
                return obj | 0;
            } else {
                dataView.setFloat64(0, obj, true);
                return (dataView.getInt32(0, true) * 31 | 0) + dataView.getInt32(4, true) | 0;
            }
        }
        
        const hashCodes = new WeakMap();
        function getObjectHashCode(obj) {
            const res = hashCodes.get(obj);
            if (res === undefined) {
                const POW_2_32 = 4294967296;
                const hash = (Math.random() * POW_2_32) | 0;
                hashCodes.set(obj, hash);
                return hash;
            }
            return res;
        }
        
        function getStringHashCode(str) {
            var hash = 0;
            for (var i = 0; i < str.length; i++) {
                var code  = str.charCodeAt(i);
                hash  = (hash * 31 + code) | 0;
            }
            return hash;
        }
        
        return (obj) => {
            if (obj == null) {
                return 0;
            }
            switch (typeof obj) {
                case "object":
                case "function":
                    return getObjectHashCode(obj);
                case "number":
                    return numberHashCode(obj);
                case "boolean":
                    return obj ? 1231 : 1237;
                default:
                    return getStringHashCode(String(obj)); 
            }
        }
        })(),
        'kotlin.wasm.internal.isNullish' : (ref) => ref == null,
        'kotlin.wasm.internal.getCachedJsObject_$external_fun' : (p0, p1) => getCachedJsObject(p0, p1),
        'kotlin.js.stackPlaceHolder_js_code' : () => (''),
        'kotlin.js.message_$external_prop_getter' : (_this) => _this.message,
        'kotlin.js.name_$external_prop_setter' : (_this, v) => _this.name = v,
        'kotlin.js.kotlinException_$external_prop_getter' : (_this) => _this.kotlinException,
        'kotlin.js.kotlinException_$external_prop_setter' : (_this, v) => _this.kotlinException = v,
        'kotlin.js.JsError_$external_class_instanceof' : (x) => x instanceof Error,
        'kotlin.random.initialSeed' : () => ((Math.random() * Math.pow(2, 32)) | 0),
        'kotlin.wasm.internal.getJsClassName' : (jsKlass) => jsKlass.name,
        'kotlin.wasm.internal.getConstructor' : (obj) => obj.constructor,
        'kotlinx.io.node.sep_$external_prop_getter' : (_this) => _this.sep,
        'kotlinx.io.node.requireExists' : () => typeof require === 'function',
        'kotlinx.io.node.requireModule' : (mod) => {
             try {
                 let m = require(mod);
                 if (m) return m;
                 return null;
             } catch (e) {
                 return null;
             }
         },
        'io.ktor.util.hasNodeApi' : () => 
        (typeof process !== 'undefined' 
            && process.versions != null 
            && process.versions.node != null) ||
        (typeof window !== 'undefined' 
            && typeof window.process !== 'undefined' 
            && window.process.versions != null 
            && window.process.versions.node != null)
        ,
        'kotlinx.datetime.internal.JSJoda.DateTimeFormatter_$external_class_instanceof' : (x) => x instanceof _ref_QGpzLWpvZGEvY29yZQ_.DateTimeFormatter,
        'kotlinx.datetime.internal.JSJoda.DateTimeFormatter_$external_class_get' : () => _ref_QGpzLWpvZGEvY29yZQ_.DateTimeFormatter,
        'kotlinx.datetime.internal.JSJoda.DateTimeFormatterBuilder_$external_fun' : () => new _ref_QGpzLWpvZGEvY29yZQ_.DateTimeFormatterBuilder(),
        'kotlinx.datetime.internal.JSJoda.appendOffset_$external_fun' : (_this, p0, p1) => _this.appendOffset(p0, p1),
        'kotlinx.datetime.internal.JSJoda.appendOffsetId_$external_fun' : (_this, ) => _this.appendOffsetId(),
        'kotlinx.datetime.internal.JSJoda.parseCaseInsensitive_$external_fun' : (_this, ) => _this.parseCaseInsensitive(),
        'kotlinx.datetime.internal.JSJoda.toFormatter_$external_fun' : (_this, p0) => _this.toFormatter(p0),
        'kotlinx.datetime.internal.JSJoda.STRICT_$external_prop_getter' : (_this) => _this.STRICT,
        'kotlinx.datetime.internal.JSJoda.Companion_$external_object_getInstance' : () => _ref_QGpzLWpvZGEvY29yZQ_.ResolverStyle
    }
    
    // Placed here to give access to it from externals (js_code)
    let wasmInstance;
    let require; 
    let wasmExports;

    const isNodeJs = (typeof process !== 'undefined') && (process.release.name === 'node');
    const isDeno = !isNodeJs && (typeof Deno !== 'undefined')
    const isStandaloneJsVM =
        !isDeno && !isNodeJs && (
            typeof d8 !== 'undefined' // V8
            || typeof inIon !== 'undefined' // SpiderMonkey
            || typeof jscOptions !== 'undefined' // JavaScriptCore
        );
    const isBrowser = !isNodeJs && !isDeno && !isStandaloneJsVM && (typeof window !== 'undefined' || typeof self !== 'undefined');
    
    if (!isNodeJs && !isDeno && !isStandaloneJsVM && !isBrowser) {
      throw "Supported JS engine not detected";
    }

    const wasmFilePath = './Feed-shared.wasm';

    const importObject = {
        js_code,
        intrinsics: {
            tag: wasmTag
        },


    };
    
    try {
      if (isNodeJs) {
        const module = await import(/* webpackIgnore: true */'node:module');
        const importMeta = import.meta;
        require = module.default.createRequire(importMeta.url);
        const fs = require('fs');
        const url = require('url');
        const filepath = import.meta.resolve(wasmFilePath);
        const wasmBuffer = fs.readFileSync(url.fileURLToPath(filepath));
        const wasmModule = new WebAssembly.Module(wasmBuffer);
        wasmInstance = new WebAssembly.Instance(wasmModule, importObject, { builtins: [''] });
      }
      
      if (isDeno) {
        const path = await import(/* webpackIgnore: true */'https://deno.land/std/path/mod.ts');
        const binary = Deno.readFileSync(path.fromFileUrl(import.meta.resolve(wasmFilePath)));
        const module = await WebAssembly.compile(binary);
        wasmInstance = await WebAssembly.instantiate(module, importObject, { builtins: [''] });
      }
      
      if (isStandaloneJsVM) {
        const wasmBuffer = read(wasmFilePath, 'binary');
        const wasmModule = new WebAssembly.Module(wasmBuffer);
        wasmInstance = new WebAssembly.Instance(wasmModule, importObject, { builtins: [''] });
      }
      
      if (isBrowser) {
        wasmInstance = (await WebAssembly.instantiateStreaming(fetch(new URL('./Feed-shared.wasm',import.meta.url).href), importObject, { builtins: [''] })).instance;
      }
    } catch (e) {
      if (e instanceof WebAssembly.CompileError) {
        let text = `Please make sure that your runtime environment supports the latest version of Wasm GC and Exception-Handling proposals.
For more information, see https://kotl.in/wasm-help
`;
        if (isBrowser) {
          console.error(text);
        } else {
          const t = "\n" + text;
          if (typeof console !== "undefined" && console.log !== void 0) 
            console.log(t);
          else 
            print(t);
        }
      }
      throw e;
    }
    
    wasmExports = wasmInstance.exports;
    if (runInitializer) {
        wasmExports._initialize();
    }

    return { instance: wasmInstance,  exports: wasmExports };
}
