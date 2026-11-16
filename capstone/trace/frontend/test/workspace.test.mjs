import test from "node:test";
import assert from "node:assert/strict";
import { allowed } from "../src/app/permissions.ts";
import { allowNavigation, beforeLeave } from "../src/app/navigationGuard.ts";
import {
  hasMappingConflict,
  readCsvHeaders,
} from "../src/features/integration/importMapping.ts";

test("workspace write actions follow the selected organization's team role", () => {
  const teamRoles = { writer: "EDITOR", reader: "VIEWER" };
  assert.equal(
    allowed({ organizationRole: "MEMBER", teamRoles }, "writer"),
    true,
  );
  assert.equal(
    allowed({ organizationRole: "MEMBER", teamRoles }, "reader"),
    false,
  );
  assert.equal(
    allowed({ organizationRole: "VIEWER", teamRoles }, "writer"),
    false,
  );
  assert.equal(
    allowed({ organizationRole: "ADMIN", teamRoles: {} }, "reader"),
    true,
  );
  assert.equal(allowed({ organizationRole: null, teamRoles }, "writer"), false);
});

test("the workspace can defer navigation until the editor confirms discard", (context) => {
  const oldWindow = globalThis.window;
  const eventTarget = new EventTarget();
  globalThis.window = eventTarget;
  context.after(() => {
    if (oldWindow) globalThis.window = oldWindow;
    else delete globalThis.window;
  });

  let calls = 0;
  const navigate = () => {
    calls++;
  };
  if (allowNavigation(navigate)) navigate();
  assert.equal(calls, 1);

  let resume;
  eventTarget.addEventListener(
    beforeLeave,
    (event) => {
      event.preventDefault();
      resume = event.detail;
    },
    { once: true },
  );
  if (allowNavigation(navigate)) navigate();
  assert.equal(calls, 1);
  resume();
  assert.equal(calls, 2);
});

test("CSV mapping preserves quoted and blank source headers", () => {
  assert.deepEqual(
    readCsvHeaders('\uFEFF"db,name","a""b",,type\r\nDB,value,,NUMBER'),
    ["db,name", 'a"b', "", "type"],
  );
  assert.deepEqual(readCsvHeaders('"multi\nline",type\nname,NUMBER'), [
    "multi\nline",
    "type",
  ]);
});

test("mapping checks catch duplicate source and canonical fields", () => {
  assert.equal(
    hasMappingConflict(["db_name", "DATABASE"], { db_name: "database" }),
    true,
  );
  assert.equal(hasMappingConflict(["name", "name"], {}), true);
  assert.equal(
    hasMappingConflict(["db_name", "type"], {
      db_name: "database",
      type: "data_type",
    }),
    false,
  );
});
