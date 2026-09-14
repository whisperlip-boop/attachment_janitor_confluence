# Attachment Janitor

Read-only attachment storage reporting for Confluence Server / Data Center 7.x.

Confluence does not tell an administrator how much disk each space's attachments occupy, and
it does not show old versions at all: re-upload `spec.xlsx` thirty times and the page still
shows one file, while thirty files sit on disk. This app measures both and puts them in one
table.

**It never deletes anything.** v1 is a report. That is a deliberate limit, not an unfinished
feature — the point is to give an administrator the numbers they need before deciding, and a
tool that might delete something is a tool an administrator hesitates to install.

## What it shows

The app lives at **Confluence administration → Configuration → Attachment storage report**
(`/plugins/servlet/attachment-janitor`). A Confluence administrator account and a secure
administrator session are required. Every screen has a Korean/English toggle, and the language
travels in the URL so a link opens in the language you shared it in.

**Spaces** — one row per space: file count, total size, how much of it is superseded versions,
how much is unreferenced, how many attachments are used from outside their own page, and how
many are duplicated.

**Space detail** — every attachment in one space with a status, flags, and the evidence.
Filter by status, flag, file type, size or name.

*Status* answers one question — what breaks if you remove this file:

| Status | Given when | What it means for you |
|---|---|---|
| In use | The page it is attached to references it | Leave it alone |
| Used elsewhere | Something other than its own page references it | Most dangerous to remove; open the evidence first |
| History only | Only past versions of a body reference it | Today nothing breaks; old versions will show a broken image |
| On a draft | Attached to an unsaved draft | Not judged yet, not "unused" |
| Unreferenced | Nothing this app can see references it | The first place to look |

*Flags* answer a different question — how much it costs you: many old versions, duplicate,
large, untouched. They are independent of status and a file can carry several. Thresholds are
configurable.

**Duplicates** — files with the same contents, grouped, with what you would reclaim by keeping
one copy.

**Settings** — the thresholds above, whether past page versions are examined, how duplicates
are compared, and how many scans to keep.

**Help** — an in-app page explaining every status and flag, with the current thresholds filled
in from your settings. Linked from the bottom of each screen.

Every table exports to CSV.

## How it works

Pressing **Scan now** runs five phases in the background — read attachments, read page bodies,
match references, compare file contents, save — and stores a snapshot. Every screen reads the
stored snapshot and states the time it was taken, so opening a page never triggers work. The
scan reports which phase it is in and can be cancelled; a second scan request while one is
running is refused rather than queued.

References are found by walking the `ri:attachment` elements in each body rather than by
matching a list of macros, so macros this app has never heard of are still counted. Duplicates
are found in two stages: group by exact size and extension first, then hash only what survives,
so most files are never read from disk at all.

Superseded versions cost one extra query per attachment that has any, and none for attachments
uploaded once. That query is the expensive part: measured on its own, the attachment phase of a
2,000-attachment test instance took 1.2 seconds when 17 files had older versions and 16 to 20
seconds when 1,048 did. A full scan of that same instance — attachments, every page body, the
match and the duplicate stages — takes 20 to 23 seconds, and 27 seconds with page history and
full hashing turned on. An instance where most files have been re-uploaded is the slow case —
which is also the instance this report is most useful on, so run the first scan outside
business hours.

## What the numbers do not include

- **Search indexes, thumbnails and extracted text.** The figures are the attachment files
  themselves as Confluence records them, so they are smaller than the home directory.
- **Attachments Confluence has already marked deleted** — a trashed page, or an attachment
  deleted on its own. Their files stay on disk until Confluence cleans them up, but the API
  this report reads does not return them, so they cannot be counted honestly and are left out
  rather than shown as zero.
- **Anything outside attachments**: the database itself, backups, Synchrony.
- **Past page versions**, unless you switch that on in Settings. With it off, an attachment
  used only by an older version of a page is reported as unreferenced and labelled
  "history not checked".
- **Page templates and blueprints.** An attachment used only by a template reads as
  unreferenced.

"Unreferenced" means no reference was found where this app looks. A body it could not parse,
an external system linking straight to the download URL, or a macro storing a filename in a
way it does not recognise all produce the same answer — so the screen reports parse failures
and unresolvable references as their own numbers rather than folding them into the totals.

## Requirements

- Confluence Server / Data Center 7.x (built against 7.8.1, deployment target 7.12.3)
- Java 8
- Confluence administrator rights to open the screen

## Building

```bash
DOWNLOADS=/path/to/output ./build.sh
```

Or, to build and install straight into a running instance:

```bash
CONFLUENCE_BASE=http://<host>:<port> CONFLUENCE_USER=<admin> CONFLUENCE_PASS='...' ./deploy.sh
```

Both scripts regenerate the i18n bundles from `i18n/*.properties.src` first; do not edit the
generated `.properties` files under `src/main/resources`.

## REST

All endpoints require a Confluence administrator; anonymous callers get 401, other users 403.

| Method | Path | |
|---|---|---|
| GET | `/rest/attachment-janitor/1.0/report/spaces` | Stored snapshot plus one row per space |
| GET | `/rest/attachment-janitor/1.0/report/space?key=X` | One space's attachments with status, flags and evidence |
| GET | `/rest/attachment-janitor/1.0/report/duplicates` | Duplicate groups and their members |
| GET | `/rest/attachment-janitor/1.0/report/settings` | Current thresholds |
| PUT | `/rest/attachment-janitor/1.0/report/settings` | Change them (values are clamped, the response is what was stored) |
| POST | `/rest/attachment-janitor/1.0/report/scan` | Start a scan (202, or 409 if one is running) |
| GET | `/rest/attachment-janitor/1.0/report/scan` | Progress, including which phase |
| DELETE | `/rest/attachment-janitor/1.0/report/scan` | Cancel the running scan |
| GET | `.../spaces.csv`, `.../space.csv?key=X`, `.../duplicates.csv` | The same tables as CSV |

Statuses and flags travel as codes, never as sentences: one stored result is rendered in
whatever language the reader chose.

## Roadmap

Everything here is read-only. Acting on what it finds — removing files, cleaning up old
versions, moving attachments — is deliberately not part of this version and would need its own
design: a confirmation step, a dry run, and an audit log.

## Licence

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Not affiliated with Atlassian.
