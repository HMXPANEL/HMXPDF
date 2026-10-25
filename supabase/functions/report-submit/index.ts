import { createClient } from "jsr:@supabase/supabase-js@2";
import {
  handleReportRequest,
  type ReportRecord,
  type ReportStore,
} from "./handler.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

const db = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});
const hmx = db.schema("hmx");

const store: ReportStore = {
  async consumeRateLimit(bucket, limit, windowSeconds) {
    const { data, error } = await hmx.rpc("consume_rate_limit", {
      p_bucket: bucket,
      p_limit: limit,
      p_window_seconds: windowSeconds,
    });
    if (error) {
      console.error("report-submit: rate limiter failed", error.code);
      return "error";
    }
    return data === true ? "allowed" : "limited";
  },

  async find(installId, clientReportId) {
    const { data, error } = await hmx
      .from("reports")
      .select("id,status,received_at")
      .eq("install_id", installId)
      .eq("client_report_id", clientReportId)
      .maybeSingle();
    if (error) {
      console.error("report-submit: duplicate lookup failed", error.code);
      return { status: "error" };
    }
    return data ? { status: "found", row: data } : { status: "missing" };
  },

  async insert(record: ReportRecord) {
    const { data, error } = await hmx
      .from("reports")
      .insert(record, {
        onConflict: "install_id,client_report_id",
        ignoreDuplicates: true,
      })
      .select("id,status,received_at");

    if (error) {
      if (error.code === "23514") {
        console.error("report-submit: database check failed", error.code);
        return { status: "invalid" };
      }
      console.error("report-submit: insert failed", error.code);
      return { status: "error" };
    }

    const row = Array.isArray(data) ? data[0] : null;
    return row ? { status: "inserted", row } : { status: "duplicate" };
  },
};

Deno.serve((req: Request): Promise<Response> =>
  handleReportRequest(req, {
    store,
    configured: Boolean(SUPABASE_URL && SERVICE_ROLE_KEY),
  })
);
