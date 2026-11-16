# Trace frontend

React + TypeScript + Vite. See the repository [development guide](../README.md)
for database provisioning, server-only local identity configuration, and startup.

```sh
npm ci
npm run dev
npm run build
npm run lint
```

The loopback Vite server proxies `/api` to Spring Boot at 127.0.0.1:8080.
For another backend port: `TRACE_BACKEND_URL=http://127.0.0.1:8081 npm run dev`.
The browser never calls Python or supplies tenant identity.

Screens: metadata CSV selection and preview, warnings/errors, confirmed commit and
actual statistics, catalog search/pagination, dataset details and column listing.
All runtime records come from Spring Boot. The local organization indicator is
read-only. This application is not ready for public deployment before Step 09.

There is no frontend unit-test runner. Build and lint plus the documented real
browser acceptance checks are the current verification steps.
