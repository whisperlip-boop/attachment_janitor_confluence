# Attachment Janitor

Attachment storage reporting for Confluence Server 7.x, with one clean-up action.

Confluence does not tell an administrator how much disk each space's attachments occupy, and
it does not show old versions at all: re-upload `spec.xlsx` thirty times and the page still
shows one file, while thirty files sit on disk. This app measures both and puts them in one
table.

**The only thing it can remove is superseded versions of an attachment** — never an
attachment, never a page, never anything you have not seen in a preview first. That narrowness
is deliberate. The point is to give an administrator the numbers they need before deciding, and
a tool that might destroy something unexpectedly is a tool an administrator hesitates to
install. Removing older versions is the one action that cannot break a page, and the storage
format is why: a page body names an attachment by file name, and has no field in which it
could name a version.

## What it shows

The app lives at **Confluence administration → Configuration → Attachment storage report**
(`/plugins/servlet/attachment-janitor`). A Confluence administrator account and a secure
administrator session are required. Every screen has a Korean/English toggle, and the language
travels in the URL so a link opens in the language you shared it in.

**Spaces** — one row per space: file count, total size, how much of it is superseded versions,
how much is unreferenced, how many attachments are used from outside their own page, and how
many are duplicated.

**Space detail** — every attachment in one space with a status, flags, and the evidence.
A space's own logo is an attachment too, and nothing in any page body refers to it; the report
recognises it as the space logo rather than calling it unreferenced. Other files attached
alongside it are still reported as unreferenced, because they genuinely are.
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

Reading page bodies is where the time goes, and the cost is simply the number of bodies: about
45 to 60 milliseconds each, whether it is a current page or an older version. On a test instance
of 2,546 attachments across 553 current bodies a full scan takes 46 seconds, of which the body
pass is most of it. **Turning on history scanning multiplied the bodies by ten — 5,131 of them —
and the scan took 338 seconds**, 93% of that in the body pass. History is not expensive in
itself; it just means ten times as much to read. That is why it is off by default, and why
attachments are then labelled "Unreferenced (history not checked)" rather than plain
unreferenced.

Memory is not the constraint. Across that hundredfold increase in bodies, peak heap during the
scan grew by 65 MB (328 MB to 393 MB above the pre-scan baseline) and nothing was retained after
the scan. Repeat runs varied by tens of seconds, so read the figures as a range, not a benchmark.
Instances far larger than 2,546 attachments have not been measured. An instance where most files have been re-uploaded is the slow case —
which is also the instance this report is most useful on, so run the first scan outside
business hours.

## What the numbers do not include

- **Search indexes, thumbnails and extracted text.** The figures are the attachment files
  themselves as Confluence records them, so they are smaller than the home directory.
- **Page templates.** A template has no container of its own, so Confluence stores an image
  in a template as a URL rather than as an attachment reference - even when the attachment is
  fully identified. There is nothing there for this report to find, and it does not follow
  URLs, so templates are neither scanned nor counted.
- **Attachments Confluence has already marked deleted** — a trashed page, or an attachment
  deleted on its own. Their files stay on disk until Confluence cleans them up, but the API
  this report reads does not return them, so they cannot be counted honestly and are left out
  rather than shown as zero. On the test instance that was 8 attachments totalling 3.9 KB, and
  7 of the 8 sat under pages that were themselves in the trash - which no reasonably cheap API
  will return.
- **Anything outside attachments**: the database itself, backups, Synchrony.
- **Past page versions**, unless you switch that on in Settings. With it off, an attachment
  used only by an older version of a page is reported as unreferenced and labelled
  "history not checked".
- **Page templates and blueprints.** A template cannot hold an attachment reference at all —
  see the limits above — so an attachment used only by a template reads as unreferenced.

"Unreferenced" means no reference was found where this app looks. A body it could not parse,
an external system linking straight to the download URL, or a macro storing a filename in a
way it does not recognise all produce the same answer — so the screen reports parse failures
and unresolvable references as their own numbers rather than folding them into the totals.

## Requirements

- Confluence Server 7.x (built against 7.8.1, deployment target 7.12.3)
- Java 8
- Confluence administrator rights to open the screen

**This app does not declare Data Center compatibility.** A scan and a cleanup are serialised
by a cluster lock rather than an in-process flag, so the code is written for more than one
node, but that has only ever been exercised on a single node — there is no cluster here to
test against. A defect that only a real cluster shows up has already been found once by
reading the code, so the claim is kept to what has been verified. Two things are known to be
missing for multi-node use even if the lock holds: progress and Cancel are node-local, so a
browser served by another node sees nothing while a job runs and cannot stop it.

## Long-running cleanup

Removing older versions runs in the background on the server, not inside the HTTP request.
A cleanup costs roughly 105ms per file, so a large selection takes minutes — long enough
that a reverse proxy or load balancer in front of Confluence closes an idle connection
before the work finishes. The browser starts the job, gets a batch id back immediately, and
polls for progress; closing the page does not stop the work, and reopening the screen
re-attaches to a job that is still running. Cancel stops the job before the next file.
**Versions already removed are not restored** — Confluence has no trash for attachment
versions.

Every cleanup writes two kinds of record: one row per file saying which version numbers were
removed, and one summary row per run. Both are kept for the number of days set on the
Settings screen (365 by default, minimum 30) and are deleted the next time a cleanup runs.

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

## Removing older versions

Confluence can delete one attachment version at a time, buried in a page's attachment history
behind a confirmation screen. It cannot tell you where the volume is, and it cannot do a
hundred of them. That gap is what this action fills.

On a space's file list, files that have older versions get a checkbox. Choose how many recent
versions to keep — the count includes the current one, and the default is 3 — then preview.
The preview is read live at that moment rather than taken from the last scan, and it lists
every file, how many versions go, and how much is reclaimed. Only then is there a button.

What the app guarantees:

- **The current version is never touched.** Two independent checks enforce it, because
  Confluence itself does not: its own API will happily delete the current version and silently
  revert the file's contents.
- **Status is irrelevant and is not used to hide anything.** In use, Used elsewhere,
  Unreferenced — removing older versions is equally safe for all of them.
- **Every file is re-read at the moment of removal** and compared with the preview. A file
  that changed in between is skipped and reported; the rest proceed.
- **Everything is logged** — file, versions, bytes, who, when — including skips and failures.
- **Removal asks for your password again**, even though reading does not.

What it cannot do:

- **Undo.** Confluence has no trash for attachment versions. Rows and files go immediately.
- **Warn about version-pinned links.** If something links straight to a download URL with
  `?version=3` in it, that link breaks and this app cannot see it, because it does not follow
  URLs.

Removing an attachment, or moving one, is still done in Confluence.

## Roadmap

Deleting unreferenced attachments is deliberately **not** here. The Unreferenced label is
currently biased towards over-reporting — page templates are not scanned, past versions are off
by default, and attachments already marked deleted are not counted — and acting destructively
on a label the app itself describes as biased would be the wrong order to do things in.

## Licence

MIT. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Not affiliated with Atlassian.
