import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import SwaggerParser from "@apidevtools/swagger-parser";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const toolsDirectory = path.dirname(fileURLToPath(import.meta.url));
const contractsDirectory = path.resolve(toolsDirectory, "..");
const manifestPath = path.join(contractsDirectory, "manifest.json");
const manifest = JSON.parse(await fs.readFile(manifestPath, "utf8"));
const packageInfo = JSON.parse(await fs.readFile(path.join(contractsDirectory, "package.json"), "utf8"));

const failures = [];
const successes = [];

function fail(message) {
  failures.push(message);
}

function pass(message) {
  successes.push(message);
}

async function readJson(relativePath) {
  const absolutePath = path.join(contractsDirectory, relativePath);
  return JSON.parse(await fs.readFile(absolutePath, "utf8"));
}

if (!/^\d+\.\d+\.\d+$/.test(manifest.contractVersion)) {
  fail(`manifest.contractVersion 不是语义化版本：${manifest.contractVersion}`);
}
if (packageInfo.version !== manifest.contractVersion) {
  fail("package.json version 与 manifest.contractVersion 不一致");
}

const ajv = new Ajv2020({ allErrors: true, strict: true, strictRequired: false });
addFormats(ajv);
ajv.addKeyword({ keyword: "x-contract-version", schemaType: "string" });

const schemas = [];
for (const schemaPath of manifest.schemas) {
  try {
    const schema = await readJson(schemaPath);
    if (!ajv.validateSchema(schema)) {
      fail(`${schemaPath} 不是合法的 Draft 2020-12 Schema：${ajv.errorsText(ajv.errors)}`);
      continue;
    }
    if (schema["x-contract-version"] !== manifest.contractVersion) {
      fail(`${schemaPath} 的 x-contract-version 与 manifest 不一致`);
    }
    if (schema.properties?.schemaVersion?.const !== manifest.contractVersion) {
      fail(`${schemaPath} 的 schemaVersion const 与 manifest 不一致`);
    }
    schemas.push({ path: schemaPath, schema });
    ajv.addSchema(schema);
    pass(`JSON Schema：${schemaPath}`);
  } catch (error) {
    fail(`${schemaPath} 读取或注册失败：${error.message}`);
  }
}

try {
  const openapiPath = path.join(contractsDirectory, manifest.openapi);
  const api = await SwaggerParser.validate(openapiPath);
  if (api.openapi !== "3.1.0") {
    fail(`OpenAPI 版本必须为 3.1.0，实际为 ${api.openapi}`);
  }
  if (api.info?.version !== manifest.contractVersion) {
    fail(`OpenAPI info.version 与 manifest 不一致`);
  }
  if (!Object.keys(api.paths ?? {}).length) {
    fail("OpenAPI 未定义任何路径");
  } else {
    pass(`OpenAPI：${manifest.openapi}`);
  }
} catch (error) {
  fail(`OpenAPI 校验失败：${error.message}`);
}

for (const { path: schemaPath, schema } of schemas) {
  const baseName = path.basename(schemaPath, ".schema.json");
  const validate = ajv.getSchema(schema.$id);
  for (const expectation of ["valid", "invalid"]) {
    const examplePath = `examples/${manifest.apiVersion}/${baseName}.${expectation}.json`;
    try {
      const example = await readJson(examplePath);
      const actual = validate(example);
      const expected = expectation === "valid";
      if (actual !== expected) {
        fail(`${examplePath} 预期 ${expectation}，实际 ${actual ? "valid" : ajv.errorsText(validate.errors)}`);
      } else {
        pass(`示例：${examplePath}`);
      }
    } catch (error) {
      fail(`${examplePath} 读取或校验失败：${error.message}`);
    }
  }
}

for (const message of successes) {
  console.log(`PASS ${message}`);
}

if (failures.length) {
  for (const message of failures) {
    console.error(`FAIL ${message}`);
  }
  process.exitCode = 1;
} else {
  console.log(`\n契约校验通过：${successes.length} 项。`);
}
