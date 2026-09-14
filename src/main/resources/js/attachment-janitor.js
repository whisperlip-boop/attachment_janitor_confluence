/*
 * 화면 네 개(랭킹 · 스페이스 상세 · 중복 · 설정)와 설명서의 목차를 그린다.
 *
 * 조용히 실패하지 않는다. 요청이 401/403 이면 폴링을 멈추고 버튼을 되살리고 이유를 낸다 —
 * 그게 없으면 관리자 세션 만료가 "눌렀는데 아무 일도 없다"로 보인다.
 *
 * 라벨과 배지 문구는 서버가 넘겨준 번들에서 꺼낸다. REST 는 코드만 보내므로 같은 결과
 * 한 벌을 한국어 화면과 영어 화면이 함께 볼 수 있다.
 */
(function () {
    'use strict';

    var root = document.querySelector('.aj-app');
    if (!root) {
        return;
    }

    var base = root.getAttribute('data-base') || '';
    var screen = root.getAttribute('data-screen') || 'SPACES';
    var lang = root.getAttribute('data-lang') || 'en';
    var spaceKey = root.getAttribute('data-space') || '';

    function codeList(attribute) {
        var raw = root.getAttribute(attribute) || '';
        return raw.split(',').filter(function (code) {
            return code !== '';
        });
    }
    var api = base + '/rest/attachment-janitor/1.0/report';

    var strings = {};
    try {
        strings = JSON.parse(document.getElementById('aj-i18n').textContent);
    } catch (error) {
        strings = {};
    }

    var bannerBox = document.getElementById('aj-banner');
    var summaryBox = document.getElementById('aj-summary');
    var filterBox = document.getElementById('aj-filters');
    var tableBox = document.getElementById('aj-table');
    var asOf = document.getElementById('aj-asof');
    var scanButton = document.getElementById('aj-scan');
    var cancelButton = document.getElementById('aj-cancel');
    var csvLink = document.getElementById('aj-csv');

    var pollTimer = null;
    var lastPayload = null;
    var filters = {label: '', badge: '', extension: '', minBytes: 0, query: ''};
    /* 표를 한 장에 다 그리면 실제 인스턴스에서 수천 줄이 된다. 쪽으로 끊는다. */
    var paging = {size: 50, page: 1};
    /* 고른 첨부. **렌더 밖에 둔다** — 표 안에 두면 쪽을 넘기는 순간 선택이 날아간다.
       그리고 "전체 선택"은 보고 있는 쪽이 아니라 필터에 걸린 전체를 뜻한다. */
    var picked = {};
    var lastVisible = [];
    /* 방금 정리한 것. 표는 **저장된 스캔**이라 우리가 지운 것을 모르고, 다음 스캔까지
       "구버전 27개"라고 계속 말한다. 그 상태로 두면 지울 것 없는 파일에 체크박스가
       남아서 눌러도 아무 일도 안 일어난다. 새 스캔이 오면 버린다. */
    var cleaned = {};
    var cleanedForRun = null;
    var keepVersions = 3;

    /* ------------------------------------------------------------------ 공통 */

    function text(key, fallback) {
        return Object.prototype.hasOwnProperty.call(strings, key) ? strings[key]
            : (fallback === undefined ? key : fallback);
    }

    function format(template, a, b) {
        return String(template).replace('{0}', a === undefined ? '' : a)
            .replace('{1}', b === undefined ? '' : b);
    }

    function escape(value) {
        return String(value === null || value === undefined ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    /* 용량 질문은 2진 단위로 묻고 답한다. */
    function bytes(value) {
        var size = Number(value) || 0;
        if (size < 1024) {
            return size + ' B';
        }
        var units = ['KB', 'MB', 'GB', 'TB', 'PB'];
        var index = -1;
        do {
            size = size / 1024;
            index++;
        } while (size >= 1024 && index < units.length - 1);
        return (size >= 100 ? size.toFixed(0) : size.toFixed(1)) + ' ' + units[index];
    }

    function number(value) {
        return (Number(value) || 0).toLocaleString();
    }

    function localTime(iso) {
        if (!iso) {
            return '';
        }
        var when = new Date(iso);
        if (isNaN(when.getTime())) {
            return iso;
        }
        function pad(part) {
            return (part < 10 ? '0' : '') + part;
        }
        return when.getFullYear() + '-' + pad(when.getMonth() + 1) + '-' + pad(when.getDate())
            + ' ' + pad(when.getHours()) + ':' + pad(when.getMinutes());
    }

    function withLang(url) {
        return url + (url.indexOf('?') < 0 ? '?' : '&') + 'lang=' + encodeURIComponent(lang);
    }

    function request(method, path, onDone, body) {
        var xhr = new XMLHttpRequest();
        xhr.open(method, api + path, true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.setRequestHeader('X-Atlassian-Token', 'no-check');
        if (body !== undefined) {
            xhr.setRequestHeader('Content-Type', 'application/json');
        }
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 401 || xhr.status === 403) {
                stopPolling();
                idleButtons();
                /* 웹수도가 풀린 것과 권한이 없는 것은 다르다. 전자는 다시 인증하면
                   되는데 "권한이 없습니다"만 내면 관리자가 할 수 있는 일이 없어진다. */
                var why = null;
                try {
                    why = JSON.parse(xhr.responseText);
                } catch (error) {
                    why = null;
                }
                if (why && why.websudo) {
                    banner('warn', text('aj.error.websudo'));
                    return;
                }
                banner('error', text('aj.error.forbidden'));
                return;
            }
            if (xhr.status === 0 || xhr.status >= 500) {
                stopPolling();
                idleButtons();
                banner('error', text('aj.error.network'));
                return;
            }
            var payload = null;
            try {
                payload = JSON.parse(xhr.responseText);
            } catch (error) {
                payload = null;
            }
            onDone(xhr.status, payload);
        };
        xhr.send(body === undefined ? null : body);
    }

    function banner(kind, message) {
        if (!bannerBox) {
            return;
        }
        bannerBox.innerHTML = message
            ? '<div class="aj-banner aj-banner-' + kind + '">' + escape(message) + '</div>' : '';
    }

    function idleButtons() {
        if (!scanButton) {
            return;
        }
        scanButton.disabled = false;
        scanButton.textContent = text('aj.action.scan');
        if (cancelButton) {
            cancelButton.hidden = true;
        }
    }

    function busyButtons() {
        if (!scanButton) {
            return;
        }
        scanButton.disabled = true;
        scanButton.textContent = text('aj.action.scanning');
        if (cancelButton) {
            cancelButton.hidden = false;
        }
    }

    function stopPolling() {
        if (pollTimer) {
            window.clearTimeout(pollTimer);
            pollTimer = null;
        }
    }

    /* ------------------------------------------------------- 라벨 · 배지 표기 */

    function labelName(code, historyScanned) {
        /* 이력을 안 본 채로 "고아"라고 하면 검사하지 않은 것을 검사했다고 말하는 것이 된다. */
        if (code === 'orphan' && historyScanned === false) {
            return text('aj.label.name.orphan_unchecked');
        }
        return text('aj.label.name.' + code);
    }

    function labelChip(code, historyScanned) {
        return '<span class="aj-label aj-label-' + escape(code) + '" title="'
            + escape(text('aj.label.cond.' + code)) + '">'
            + escape(labelName(code, historyScanned)) + '</span>';
    }

    function badgeChips(codes) {
        if (!codes) {
            return '<span class="aj-none">-</span>';
        }
        return codes.split(' ').map(function (code) {
            return '<span class="aj-badge aj-badge-' + escape(code) + '" title="'
                + escape(text('aj.badge.desc.' + code)) + '">'
                + escape(text('aj.badge.name.' + code)) + '</span>';
        }).join(' ');
    }

    function contentUrl(contentId) {
        return base + '/pages/viewpage.action?pageId=' + encodeURIComponent(contentId);
    }

    /* --------------------------------------------------------------- 배너 모음 */

    function renderBanners(payload) {
        var run = payload.run;
        var shown = payload.shown;
        var progress = payload.progress;
        var parts = '';

        if (progress && progress.running) {
            var phase = text(progress.phaseKey || 'aj.phase.none');
            /* 본문 단계의 processed 는 스페이스 수라 너무 작아서 멈춘 것처럼 보인다.
               읽은 본문 수를 대신 보여준다 — 그게 실제로 움직이는 숫자다. */
            var count = progress.phase === 'BODIES' && progress.bodies
                ? progress.bodies : progress.processed;
            var label = format(text('aj.state.scanning'), phase, number(count));
            /* 총계를 모르는 단계는 비율을 지어내지 않는다. 흐르는 막대로 둔다. */
            var unknown = progress.percent < 0;
            var bar = unknown
                ? '<div class="aj-progress aj-progress-unknown"><span></span></div>'
                : '<div class="aj-progress"><span style="width:' + progress.percent
                    + '%"></span></div>';
            parts += '<div class="aj-banner aj-banner-info">' + escape(label)
                + bar + '</div>';
        }
        if (run && run.status === 'FAILED') {
            parts += '<div class="aj-banner aj-banner-error">'
                + escape(format(text('aj.state.failed'), run.errorMessage || '')) + '</div>';
        } else if (run && run.status === 'CANCELLED') {
            parts += '<div class="aj-banner aj-banner-warn">'
                + escape(text('aj.state.cancelled')) + '</div>';
        }
        if (payload.stale) {
            parts += warn(text('aj.state.stale'));
        }

        /* 아래 넷은 "이 수치가 완전하지 않다"는 고백이다. 조용히 두면 표가 완전한
           것으로 읽힌다. */
        if (shown && shown.skippedCount > 0) {
            parts += warn(format(text('aj.state.skipped'), number(shown.skippedCount)));
        }
        if (shown && shown.parseFailures > 0) {
            parts += warn(format(text('aj.state.parsefail'), number(shown.parseFailures)));
        }
        if (shown && shown.unresolvedRefs > 0) {
            parts += warn(format(text('aj.state.unresolved'), number(shown.unresolvedRefs)));
        }
        if (shown && shown.hashBudgetHit) {
            parts += warn(text('aj.state.hashbudget'));
        }
        if (shown && shown.historyScanned === false) {
            parts += '<div class="aj-banner aj-banner-info">'
                + escape(text('aj.state.nohistory')) + '</div>';
        }
        bannerBox.innerHTML = parts;
    }

    function warn(message) {
        return '<div class="aj-banner aj-banner-warn">' + escape(message) + '</div>';
    }

    function renderAsOf(shown) {
        if (!asOf) {
            return;
        }
        asOf.textContent = shown && shown.finishedAt
            ? format(text('aj.asof'), localTime(shown.finishedAt)) : '';
    }

    function tiles(items) {
        summaryBox.innerHTML = items.map(function (tile) {
            return '<div class="aj-tile' + (tile[2] || '') + '"><div class="aj-tile-label">'
                + escape(tile[0]) + '</div><div class="aj-tile-value">'
                + escape(tile[1]) + '</div></div>';
        }).join('');
    }

    function emptyMessage(payload) {
        return '<p class="aj-muted">'
            + escape(text(payload.run ? 'aj.state.noresults' : 'aj.state.never')) + '</p>';
    }

    /**
     * 표 머리 한 칸.
     *
     * `<칸 키>.tip` 이 번들에 있으면 마우스를 올렸을 때 뜻이 나온다. 칸 이름만으로
     * 뜻이 통하지 않는 칸이 있고("확보 가능 용량"이 무엇에서 확보되는지 같은),
     * 그걸 설명서에만 적어 두면 아무도 안 읽는다.
     */
    function th(key, klass) {
        var tip = strings[key + '.tip'];
        return '<th class="' + klass.trim() + '"'
            + (tip ? ' title="' + escape(tip) + '"' : '')
            + '>' + escape(text(key))
            + (tip ? '<span class="aj-tip" aria-hidden="true">?</span>' : '')
            + '</th>';
    }

    function num(value, klass) {
        if (value === null || value === undefined) {
            return '<td class="aj-num"><span class="aj-none">-</span></td>';
        }
        return '<td class="aj-num' + (klass ? ' ' + klass : '') + '">' + escape(value) + '</td>';
    }

    /* ------------------------------------------------------------ 랭킹 화면 */

    function renderSpaces(payload) {
        var shown = payload.shown;
        if (!shown) {
            summaryBox.innerHTML = '';
            tableBox.innerHTML = emptyMessage(payload);
            return;
        }
        tiles([
            [text('aj.summary.files'), number(shown.fileCount), ''],
            [text('aj.summary.total'), bytes(shown.totalBytes), ''],
            [text('aj.summary.oldversions'),
                bytes(shown.oldVersionBytes) + ' · ' + number(shown.oldVersionCount),
                ' aj-tile-accent'],
            [text('aj.summary.orphans'),
                bytes(shown.orphanBytes) + ' · ' + number(shown.orphanCount),
                ' aj-tile-accent'],
            [text('aj.summary.duplicates'),
                bytes(shown.duplicateReclaimable) + ' · ' + number(shown.duplicateGroups),
                '']
        ]);

        var spaces = payload.spaces || [];
        if (!spaces.length) {
            tableBox.innerHTML = '<p class="aj-muted">' + escape(text('aj.state.empty'))
                + '</p>';
            return;
        }
        var largest = spaces.reduce(function (top, row) {
            return Math.max(top, Number(row.totalBytes) || 0);
        }, 0) || 1;

        var head = '<table class="aj-table"><thead><tr>'
            + th('aj.col.space', '') + th('aj.col.files', ' aj-num')
            + th('aj.col.total', ' aj-num') + th('aj.col.latest', ' aj-num')
            + th('aj.col.oldversions', ' aj-num') + th('aj.col.orphans', ' aj-num')
            + th('aj.col.risky', ' aj-num') + th('aj.col.duplicates', ' aj-num')
            + '</tr></thead><tbody>';

        var body = spaces.map(function (row) {
            var total = Number(row.totalBytes) || 0;
            var old = Number(row.oldVersionBytes) || 0;
            var name = row.spaceKey
                ? (row.spaceName ? row.spaceName + ' (' + row.spaceKey + ')' : row.spaceKey)
                : text('aj.col.nospace');
            var latestWidth = Math.round((total - old) / largest * 100);
            var oldWidth = Math.round(old / largest * 100);
            var link = row.spaceKey
                ? '<a href="' + escape(withLang(base + '/plugins/servlet/attachment-janitor'
                    + '/space?key=' + encodeURIComponent(row.spaceKey))) + '">'
                    + escape(name) + '</a>'
                : escape(name);

            /* 막대에도 말을 붙인다. 색만 두면 장식으로 읽히고, 장식으로 읽히는
               색은 아무 정보도 주지 않는다. 범례는 표 아래에 따로 있다. */
            var barTitle = format(text('aj.bar.title'),
                bytes(total - old), bytes(old), bytes(largest));
            return '<tr><td>' + link
                + '<span class="aj-bar-cell" title="' + escape(barTitle) + '">'
                + '<span class="aj-bar-fill" style="width:' + latestWidth + '%"></span>'
                + '<span class="aj-bar-fill aj-bar-fill-old" style="width:' + oldWidth
                + '%;margin-top:-4px;margin-left:' + latestWidth + '%"></span>'
                + '</span></td>'
                + num(number(row.fileCount))
                + num(bytes(total))
                + num(bytes(row.latestBytes))
                + num(old > 0 ? bytes(old) + ' · ' + number(row.oldVersionCount) : null,
                    'aj-warn')
                + num(row.orphanCount > 0
                    ? bytes(row.orphanBytes) + ' · ' + number(row.orphanCount) : null,
                    'aj-warn')
                + num(row.riskyCount > 0 ? number(row.riskyCount) : null)
                + num(row.duplicateCount > 0 ? number(row.duplicateCount) : null)
                + '</tr>';
        }).join('');

        tableBox.innerHTML = head + body + '</tbody></table>'
            + '<p class="aj-legend">'
            + '<span class="aj-swatch aj-swatch-latest"></span>'
            + escape(text('aj.bar.latest'))
            + '<span class="aj-swatch aj-swatch-old"></span>'
            + escape(text('aj.bar.old'))
            + '<span class="aj-swatch aj-swatch-rest"></span>'
            + escape(text('aj.bar.rest'))
            + '</p>'
            + '<p class="aj-foot">' + escape(text('aj.note.nospace')) + '</p>';
        if (csvLink) {
            csvLink.setAttribute('href', api + '/spaces.csv');
        }
    }

    /* ------------------------------------------------------- 스페이스 상세 화면 */

    function renderDetail(payload) {
        var shown = payload.shown;
        if (!shown) {
            summaryBox.innerHTML = '';
            filterBox.innerHTML = '';
            tableBox.innerHTML = emptyMessage(payload);
            return;
        }
        if (!shown.hasDetail) {
            filterBox.innerHTML = '';
            tableBox.innerHTML = '<p class="aj-muted">' + escape(text('aj.state.nodetail'))
                + '</p>';
            return;
        }

        var rows = payload.attachments || [];
        // 저장된 스캔에 방금 정리한 것을 반영한다. 이게 없으면 표가 거짓말을 한다.
        rows.forEach(applyCleaned);

        var visible = rows.filter(matchesFilters);
        lastVisible = visible;
        var totals = visible.reduce(function (acc, row) {
            acc.files++;
            acc.bytes += (Number(row.latestBytes) || 0) + (Number(row.oldVersionBytes) || 0);
            return acc;
        }, {files: 0, bytes: 0});

        tiles([
            [text('aj.summary.spacefiles'),
                number(totals.files) + ' / ' + number(rows.length), ''],
            [text('aj.summary.total'), bytes(totals.bytes), '']
        ]);
        renderFilters(rows);

        if (!visible.length) {
            tableBox.innerHTML = '<p class="aj-muted">' + escape(text('aj.state.nomatch'))
                + '</p>';
            return;
        }

        /* 기본 정렬은 라벨의 정리 순위다. 서버가 보내 준 값을 쓴다 — 화면이 순서를 다시
           적으면 열거형과 어긋난다. */
        visible.sort(function (a, b) {
            if (a.cleanupRank !== b.cleanupRank) {
                return a.cleanupRank - b.cleanupRank;
            }
            return (b.latestBytes + b.oldVersionBytes) - (a.latestBytes + a.oldVersionBytes);
        });

        /* "구버전이 있나"가 아니라 "지금 유지 개수로 지울 것이 있나"로 가른다.
           최근 3개 유지인데 구버전이 2개뿐이면 지울 것이 없고, 그런 줄에 체크박스를
           두면 눌러도 아무 일도 안 일어난다. */
        var cleanable = visible.filter(function (row) {
            return removableAt(row, keepVersions) > 0;
        });
        var allPicked = cleanable.length > 0 && cleanable.every(function (row) {
            return picked[row.attachmentId];
        });

        var head = '<table class="aj-table"><thead><tr>'
            /* 체크박스가 무엇에 대한 것인지 칸 머리에 적는다. 그냥 두면 "이 파일을
               지운다"로 읽히는데, 실제로 지워지는 것은 [구버전] 칸이다. */
            + '<th class="aj-pick" title="' + escape(text('aj.col.pick.tip')) + '">'
            + '<input type="checkbox" id="aj-pick-all"'
            + (allPicked ? ' checked' : '')
            + (cleanable.length ? '' : ' disabled')
            + ' title="' + escape(text('aj.clean.pickall')) + '">'
            + '<span class="aj-tip" aria-hidden="true">?</span></th>'
            + th('aj.col.filename', '') + th('aj.col.container', '')
            + th('aj.col.label', '') + th('aj.col.badges', '')
            + th('aj.col.latest', ' aj-num') + th('aj.col.versions', ' aj-num')
            + th('aj.col.oldversions', ' aj-num') + th('aj.col.modified', ' aj-num')
            + th('aj.col.refs', ' aj-num')
            + '</tr></thead><tbody>';

        /* 쪽 자르기. 정렬한 뒤에 자른다 — 자르고 정렬하면 쪽마다 순서가 달라진다. */
        var pageCount = Math.max(1, Math.ceil(visible.length / paging.size));
        if (paging.page > pageCount) {
            paging.page = pageCount;
        }
        var from = (paging.page - 1) * paging.size;
        var pageRows = visible.slice(from, from + paging.size);

        var body = pageRows.map(function (row, index) {
            var container = row.containerId
                ? '<a href="' + escape(contentUrl(row.containerId)) + '">'
                    + escape(row.containerTitle || row.containerId) + '</a>'
                : escape(row.containerTitle);
            var refCell = row.refCount > 0
                ? '<a href="#" class="aj-expand" data-row="' + index + '">'
                    + escape(number(row.refCount)) + '</a>'
                : '<span class="aj-none">-</span>';
            /* 구버전이 없는 행에는 체크박스 자체를 두지 않는다 — 비활성이 아니라
               없음. 지울 것이 없는 줄에 지우는 조작을 놓지 않는다.
               **라벨로 가리지 않는다**: 구버전 삭제는 어떤 라벨에서도 본문 참조를
               깨뜨릴 수 없다(실측 31번). 라벨로 가리면 없는 규칙을 새기게 된다. */
            var removable = removableAt(row, keepVersions);
            var pick = removable > 0
                ? '<input type="checkbox" class="aj-pick-one" data-id="'
                    + escape(String(row.attachmentId)) + '"'
                    + ' title="' + escape(format(text('aj.clean.picktip'),
                        number(removable))) + '"'
                    + (picked[row.attachmentId] ? ' checked' : '') + '>'
                : '';
            return '<tr><td class="aj-pick">' + pick + '</td>'
                + '<td>' + escape(row.fileName) + '</td>'
                + '<td>' + container + '<div class="aj-meta">'
                + escape(row.containerType) + '</div></td>'
                + '<td>' + labelChip(row.label, shown.historyScanned) + '</td>'
                + '<td>' + badgeChips(row.badgeCodes) + '</td>'
                + num(bytes(row.latestBytes))
                + num(number(row.versionCount))
                + num(row.oldVersionCount > 0
                    ? bytes(row.oldVersionBytes) + ' · ' + number(row.oldVersionCount)
                    : null, 'aj-warn')
                + num(row.lastModified ? localTime(row.lastModified) : null)
                + '<td class="aj-num">' + refCell + '</td>'
                + '</tr>'
                + '<tr class="aj-refs" id="aj-refs-' + index + '" hidden><td colspan="10">'
                + renderRefs(row) + '</td></tr>';
        }).join('');

        tableBox.innerHTML = cleanBar(cleanable.length) + head + body + '</tbody></table>'
            + pager(visible.length, pageCount);
        if (csvLink) {
            csvLink.setAttribute('href',
                api + '/space.csv?key=' + encodeURIComponent(spaceKey));
        }

        bindCleanup();

        Array.prototype.forEach.call(tableBox.querySelectorAll('.aj-page'),
            function (link) {
                link.addEventListener('click', function (event) {
                    event.preventDefault();
                    paging.page = Number(link.getAttribute('data-page')) || 1;
                    renderDetail(lastPayload);
                    // 다음 쪽 첫 줄이 화면 밖이면 눌러 놓고 아무 일도 안 일어난 것처럼 보인다.
                    tableBox.scrollIntoView({block: 'start'});
                });
            });

        Array.prototype.forEach.call(tableBox.querySelectorAll('.aj-expand'),
            function (link) {
                link.addEventListener('click', function (event) {
                    event.preventDefault();
                    var target = document.getElementById(
                        'aj-refs-' + link.getAttribute('data-row'));
                    target.hidden = !target.hidden;
                });
            });
    }




    /**
     * 방금 정리한 만큼 행의 수치를 깎는다.
     *
     * 스캔 결과를 서버에서 고쳐 쓰지 않는 것은 의도다 — 그러면 화면이 스캔한 적 없는
     * 상태를 스캔 결과인 것처럼 보여준다. 대신 **이번 화면에서 우리가 한 일**만
     * 클라이언트가 반영하고, 표가 옛것이라는 사실은 stale 배너가 따로 말한다.
     */
    function applyCleaned(row) {
        var hit = cleaned[row.attachmentId];
        if (!hit) {
            return;
        }
        row.oldVersionCount = Math.max(0, (row.oldVersionCount || 0) - hit.versionsRemoved);
        row.oldVersionBytes = Math.max(0, (row.oldVersionBytes || 0) - hit.bytesRemoved);
        row.versionCount = Math.max(1, (row.versionCount || 1) - hit.versionsRemoved);
    }

    /**
     * 지금 "최근 N개 유지"로 이 행에서 지워질 구버전 수.
     *
     * N 은 현재 버전을 포함한 총 개수라 구버전 중 남길 수는 N-1 이다.
     * 0 이면 고를 이유가 없다 — 체크박스를 두지 않는 기준이 이것이다.
     */
    function removableAt(row, keep) {
        return Math.max(0, (row.oldVersionCount || 0) - (Math.max(1, keep) - 1));
    }

    /* --------------------------------------------------- 구버전 정리 (되돌릴 수 없다) */

    function pickedIds() {
        return Object.keys(picked).filter(function (id) {
            return picked[id];
        }).map(Number);
    }

    /** 고른 것 중 지금 유지 개수로 실제 지울 구버전이 있는 행. */
    function pickedRemovable() {
        return lastVisible.filter(function (row) {
            return picked[row.attachmentId] && removableAt(row, keepVersions) > 0;
        });
    }

    /**
     * 표 위의 조작 줄. 고른 개수 · 유지 개수 · 미리보기 단추.
     *
     * 실행 단추는 여기 없다. **미리보기를 거치지 않고 지울 수 있는 길을 두지 않는다** —
     * 되돌릴 수 없는 작업이라 검토 단계가 강제되어야 한다.
     */
    function cleanBar(cleanableCount) {
        if (!cleanableCount) {
            return '';
        }
        var chosen = pickedRemovable();
        var versions = chosen.reduce(function (sum, row) {
            return sum + removableAt(row, keepVersions);
        }, 0);
        return '<div class="aj-cleanbar">'
            + '<span class="aj-clean-count">'
            + escape(format(text('aj.clean.picked'), number(chosen.length),
                number(versions))) + '</span>'
            + '<label>' + escape(text('aj.clean.keep'))
            + ' <input type="number" id="aj-keep" min="1" max="999" value="'
            + keepVersions + '"></label>'
            + '<button type="button" id="aj-clean-preview" class="aj-button"'
            + (versions ? '' : ' disabled') + '>'
            + escape(text('aj.clean.preview')) + '</button>'
            + '<span class="aj-clean-said"></span>'
            + '<span class="aj-clean-note">' + escape(text('aj.clean.keepnote'))
            + '</span></div>';
    }

    function bindCleanup() {
        var all = document.getElementById('aj-pick-all');
        if (all) {
            all.addEventListener('change', function () {
                // 필터에 걸린 전체다. 보고 있는 쪽만이 아니다.
                lastVisible.forEach(function (row) {
                    if (removableAt(row, keepVersions) > 0) {
                        picked[row.attachmentId] = all.checked;
                    }
                });
                renderDetail(lastPayload);
            });
        }
        Array.prototype.forEach.call(tableBox.querySelectorAll('.aj-pick-one'),
            function (box) {
                box.addEventListener('change', function () {
                    picked[box.getAttribute('data-id')] = box.checked;
                    refreshCleanBar();
                });
            });

        var keep = document.getElementById('aj-keep');
        if (keep) {
            keep.addEventListener('input', function () {
                keepVersions = Math.max(1, Number(keep.value) || 1);
                /* 유지 개수가 바뀌면 지울 것이 있는 행도 바뀐다. 더 이상 지울 것이
                   없어진 행의 선택은 버린다 — 남겨 두면 "고름 3개"인데 미리보기가
                   비어 있는 상태가 된다. */
                lastVisible.forEach(function (row) {
                    if (removableAt(row, keepVersions) <= 0) {
                        delete picked[row.attachmentId];
                    }
                });
                renderDetail(lastPayload);
                var again = document.getElementById('aj-keep');
                if (again) {
                    again.focus();
                }
            });
        }
        var preview = document.getElementById('aj-clean-preview');
        if (preview) {
            preview.addEventListener('click', function () {
                askPreview();
            });
        }
    }

    /* 체크 하나 눌렀다고 표 전체를 다시 그리지 않는다 — 스크롤 위치가 튄다. */
    function refreshCleanBar() {
        var chosen = pickedRemovable();
        var versions = chosen.reduce(function (sum, row) {
            return sum + removableAt(row, keepVersions);
        }, 0);
        var label = tableBox.querySelector('.aj-clean-count');
        var button = document.getElementById('aj-clean-preview');
        if (label) {
            label.textContent = format(text('aj.clean.picked'),
                number(chosen.length), number(versions));
        }
        if (button) {
            // 지울 버전이 0 이면 눌러도 빈 미리보기가 온다. 누를 수 없게 둔다.
            button.disabled = !versions;
        }
    }

    function askPreview() {
        var ids = pickedRemovable().map(function (row) {
            return row.attachmentId;
        });
        if (!ids.length) {
            return;
        }
        nearButton('');
        banner('', '');
        request('POST', '/cleanup/preview', function (status, payload) {
            if (status !== 200 || !payload) {
                return;
            }
            showPreview(payload);
        }, JSON.stringify({ids: ids, keep: keepVersions}));
    }

    /**
     * 미리보기. 여기서만 실행 단추가 나온다.
     *
     * 수치는 **서버가 지금 읽은 것**이지 스캔 결과가 아니다. 그래서 표에 적힌 구버전
     * 수와 다를 수 있고, 다르면 그게 맞는 쪽이다.
     */
    function showPreview(preview) {
        var rows = (preview.items || []).filter(function (item) {
            return item.removeCount > 0 && !item.problem;
        });
        var problems = (preview.items || []).filter(function (item) {
            return item.problem;
        });

        if (!rows.length) {
            /* 표는 저장된 스캔이고 미리보기는 지금 읽은 것이라 어긋날 수 있다.
               그때 위쪽 배너로만 말하면 표를 보고 있는 사람에게는 "눌렀는데 아무 일도
               없다"가 된다. 단추 옆에 낸다. */
            nearButton(text('aj.clean.nothing'));
            banner('warn', text('aj.clean.nothing'));
            return;
        }

        var list = rows.map(function (item) {
            return '<tr><td>' + escape(item.fileName) + '</td>'
                + '<td class="aj-num">' + escape(number(item.currentVersion)) + '</td>'
                + '<td class="aj-num">' + escape(number(item.removeCount)) + '</td>'
                + '<td class="aj-num">' + escape(bytes(item.removeBytes)) + '</td>'
                + '<td class="aj-meta">' + escape(item.removeVersions) + '</td></tr>';
        }).join('');

        var warn = problems.length
            ? '<p class="aj-clean-warn">'
                + escape(format(text('aj.clean.problems'), number(problems.length)))
                + '</p>'
            : '';

        modal(
            text('aj.clean.confirmtitle'),
            '<p class="aj-clean-lead">'
                + escape(format(text('aj.clean.summary'),
                    number(preview.fileCount), number(preview.versionCount),
                    bytes(preview.bytes), number(preview.keep)))
                + '</p>'
            + '<p class="aj-clean-danger">' + escape(text('aj.clean.irreversible')) + '</p>'
            + warn
            + '<div class="aj-clean-list"><table class="aj-table"><thead><tr>'
            + '<th>' + escape(text('aj.col.filename')) + '</th>'
            + '<th class="aj-num">' + escape(text('aj.clean.current')) + '</th>'
            + '<th class="aj-num">' + escape(text('aj.clean.removing')) + '</th>'
            + '<th class="aj-num">' + escape(text('aj.clean.reclaim')) + '</th>'
            + '<th>' + escape(text('aj.clean.versions')) + '</th>'
            + '</tr></thead><tbody>' + list + '</tbody></table></div>',
            text('aj.clean.execute'),
            function () {
                /* 화면이 본 것을 그대로 되돌려 보낸다. 서버가 실행 직전에 다시 계산해
                   이것과 견주고, 다르면 그 첨부만 건너뛴다. */
                runCleanup(rows.map(function (item) {
                    return item.attachmentId;
                }), rows.map(function (item) {
                    return item.attachmentId + ':' + item.removeVersions;
                }));
            });
    }

    function runCleanup(ids, expect) {
        closeModal();
        banner('info', text('aj.clean.running'));
        request('POST', '/cleanup', function (status, payload) {
            if (status === 409) {
                banner('warn', text('aj.error.busy'));
                return;
            }
            if (status !== 200 || !payload) {
                return;
            }
            picked = {};
            (payload.done || []).forEach(function (item) {
                var seen = cleaned[item.attachmentId]
                    || {versionsRemoved: 0, bytesRemoved: 0};
                cleaned[item.attachmentId] = {
                    versionsRemoved: seen.versionsRemoved + item.versionsRemoved,
                    bytesRemoved: seen.bytesRemoved + item.bytesRemoved
                };
            });
            var message = format(text('aj.clean.done'),
                number(payload.filesDone), number(payload.versionsRemoved),
                bytes(payload.bytesRemoved));
            if (payload.filesSkipped || payload.filesFailed) {
                message += ' ' + format(text('aj.clean.partial'),
                    number(payload.filesSkipped), number(payload.filesFailed));
            }
            banner('warn', message);
            load();
        }, JSON.stringify({ids: ids, keep: keepVersions, expect: expect}));
    }

    /** 정리 줄 안에 내는 알림. 표를 보고 있는 사람 눈에 닿는 유일한 자리다. */
    function nearButton(message) {
        var slot = tableBox.querySelector('.aj-clean-said');
        if (slot) {
            slot.textContent = message;
        }
    }

    /* 확인 창. 되돌릴 수 없는 작업은 화면 한복판에서 물어야 한다. */
    function modal(title, html, confirmLabel, onConfirm) {
        closeModal();
        var host = document.createElement('div');
        host.className = 'aj-modal';
        host.id = 'aj-modal';
        host.innerHTML = '<div class="aj-modal-box" role="dialog" aria-modal="true">'
            + '<h2>' + escape(title) + '</h2>'
            + '<div class="aj-modal-body">' + html + '</div>'
            + '<div class="aj-modal-foot">'
            + '<button type="button" class="aj-button aj-danger" id="aj-modal-ok">'
            + escape(confirmLabel) + '</button>'
            + '<button type="button" class="aj-button" id="aj-modal-cancel">'
            + escape(text('aj.clean.cancel')) + '</button>'
            + '</div></div>';
        document.body.appendChild(host);
        document.getElementById('aj-modal-ok').addEventListener('click', onConfirm);
        document.getElementById('aj-modal-cancel').addEventListener('click', closeModal);
        host.addEventListener('click', function (event) {
            if (event.target === host) {
                closeModal();
            }
        });
        document.getElementById('aj-modal-cancel').focus();
    }

    function closeModal() {
        var host = document.getElementById('aj-modal');
        if (host && host.parentNode) {
            host.parentNode.removeChild(host);
        }
    }

    /**
     * 쪽 이동. 한 장에 다 그리면 실제 인스턴스에서 수천 줄이 된다.
     *
     * <pre>53 found · ‹ 1 2 3 … 6 ›</pre>
     *
     * 쪽이 하나뿐이면 번호도 화살표도 내지 않는다 — 누를 데가 없는 것을 그려 두면
     * 화면만 어수선해진다. 대신 총 개수는 언제나 적는다.
     */
    function pager(total, pageCount) {
        var found = '<span class="aj-page-found">'
            + escape(format(text('aj.page.found'), number(total))) + '</span>';
        if (pageCount <= 1) {
            return '<div class="aj-pager">' + found + '</div>';
        }

        function arrow(page, glyph, titleKey, off) {
            if (off) {
                return '<span class="aj-page-off" aria-hidden="true">' + glyph + '</span>';
            }
            return '<a href="#" class="aj-page aj-page-arrow" data-page="' + page
                + '" title="' + escape(text(titleKey)) + '"'
                + ' aria-label="' + escape(text(titleKey)) + '">' + glyph + '</a>';
        }

        var links = pageNumbers(paging.page, pageCount).map(function (page) {
            if (page === 0) {
                return '<span class="aj-page-gap">…</span>';
            }
            if (page === paging.page) {
                return '<span class="aj-page-on" aria-current="page">'
                    + escape(number(page)) + '</span>';
            }
            return '<a href="#" class="aj-page" data-page="' + page + '">'
                + escape(number(page)) + '</a>';
        }).join('');

        return '<div class="aj-pager">' + found
            + '<span class="aj-page-sep">·</span>'
            + '<span class="aj-page-nav">'
            + arrow(paging.page - 1, '\u2039', 'aj.page.prev', paging.page === 1)
            + links
            + arrow(paging.page + 1, '\u203A', 'aj.page.next', paging.page === pageCount)
            + '</span></div>';
    }

    /**
     * 낼 쪽 번호. 0 은 생략 표시(…)다.
     *
     * 현재 쪽 주위 세 칸에 첫 쪽과 끝 쪽을 더한다. 가장자리에서는 창을 반대쪽으로
     * 늘려 항상 세 칸이 되게 한다 — 1쪽에서 "1 2" 만 나오면 눌러 볼 데가 없어 보인다.
     */
    function pageNumbers(current, count) {
        var low = Math.max(1, current - 1);
        var high = Math.min(count, current + 1);
        while (high - low < 2 && (low > 1 || high < count)) {
            if (low > 1) {
                low--;
            } else {
                high++;
            }
        }

        var pages = [];
        if (low > 1) {
            pages.push(1);
            if (low > 2) {
                pages.push(0);
            }
        }
        for (var page = low; page <= high; page++) {
            pages.push(page);
        }
        if (high < count) {
            if (high < count - 1) {
                pages.push(0);
            }
            pages.push(count);
        }
        return pages;
    }

    /* 라벨의 근거. 펼쳐 볼 수 없는 라벨은 신뢰받지 못한다. */
    function renderRefs(row) {
        var refs = row.refs || [];
        if (!refs.length) {
            return '<span class="aj-muted">' + escape(text('aj.refs.none')) + '</span>';
        }
        var items = refs.map(function (ref) {
            return '<li>' + escape(text('aj.ref.kind.' + ref.kind)) + ' — '
                + '<a href="' + escape(contentUrl(ref.contentId)) + '">'
                + escape(ref.title || ref.contentId) + '</a>'
                + (ref.spaceKey
                    ? ' <span class="aj-meta">(' + escape(ref.spaceKey) + ')</span>' : '')
                + '</li>';
        }).join('');
        var more = row.refCount > refs.length
            ? '<p class="aj-meta">'
                + escape(format(text('aj.refs.more'), number(row.refCount - refs.length)))
                + '</p>'
            : '';
        return '<ul class="aj-ref-list">' + items + '</ul>' + more;
    }

    function matchesFilters(row) {
        if (filters.label && row.label !== filters.label) {
            return false;
        }
        if (filters.badge && (' ' + (row.badgeCodes || '') + ' ')
                .indexOf(' ' + filters.badge + ' ') < 0) {
            return false;
        }
        if (filters.extension && row.extension !== filters.extension) {
            return false;
        }
        if (filters.minBytes && (Number(row.latestBytes) || 0) < filters.minBytes) {
            return false;
        }
        if (filters.query
                && row.fileName.toLowerCase().indexOf(filters.query.toLowerCase()) < 0) {
            return false;
        }
        return true;
    }

    // 서버가 열거형을 순회해 넘겨준 목록이다. 여기에 코드를 다시 적지 않는다 —
    // 열거형에 값을 더했을 때 필터에서만 조용히 빠지는 사고를 막는다.
    var FILTER_LABELS = codeList('data-labels');
    var FILTER_BADGES = codeList('data-badges');

    function renderFilters(rows) {
        if (filterBox.getAttribute('data-built') === 'yes') {
            return;
        }
        var extensions = {};
        rows.forEach(function (row) {
            if (row.extension) {
                extensions[row.extension] = true;
            }
        });

        function select(id, labelKey, options, noAll) {
            return '<label>' + escape(text(labelKey)) + ' <select id="' + id + '">'
                + (noAll ? '' : '<option value="">' + escape(text('aj.filter.all')) + '</option>')
                + options.map(function (option) {
                    var on = id === 'aj-f-size-page' && Number(option[0]) === paging.size;
                    return '<option value="' + escape(option[0]) + '"'
                        + (on ? ' selected' : '') + '>' + escape(option[1]) + '</option>';
                }).join('') + '</select></label>';
        }

        filterBox.innerHTML =
            select('aj-f-label', 'aj.filter.label', FILTER_LABELS.map(function (code) {
                return [code, text('aj.label.name.' + code)];
            }))
            + select('aj-f-badge', 'aj.filter.badge', FILTER_BADGES.map(function (code) {
                return [code, text('aj.badge.name.' + code)];
            }))
            + select('aj-f-ext', 'aj.filter.extension',
                Object.keys(extensions).sort().map(function (ext) {
                    return [ext, ext];
                }))
            + select('aj-f-size', 'aj.filter.minsize',
                [['1048576', '1 MB'], ['10485760', '10 MB'], ['104857600', '100 MB']])
            + '<label>' + escape(text('aj.filter.name'))
            + ' <input type="search" id="aj-f-query" value=""></label>'
            + select('aj-f-size-page', 'aj.filter.perpage',
                PAGE_SIZES.map(function (size) { return [String(size), number(size)]; }), true);
        filterBox.setAttribute('data-built', 'yes');

        bind('aj-f-label', 'change', function (value) { filters.label = value; });
        bind('aj-f-badge', 'change', function (value) { filters.badge = value; });
        bind('aj-f-ext', 'change', function (value) { filters.extension = value; });
        bind('aj-f-size', 'change', function (value) { filters.minBytes = Number(value) || 0; });
        bind('aj-f-query', 'input', function (value) { filters.query = value; });
        bind('aj-f-size-page', 'change', function (value) {
            paging.size = Number(value) || 50;
        });
    }

    var PAGE_SIZES = [10, 20, 50, 100];

    function bind(id, event, apply) {
        var element = document.getElementById(id);
        if (!element) {
            return;
        }
        element.addEventListener(event, function () {
            apply(element.value);
            // 무엇을 바꾸든 1쪽으로 돌아간다. 3쪽을 보다 조건을 좁히면 빈 화면이 된다.
            paging.page = 1;
            if (lastPayload) {
                renderDetail(lastPayload);
            }
        });
    }

    /* ------------------------------------------------------------- 중복 화면 */

    function renderDuplicates(payload) {
        var shown = payload.shown;
        if (!shown) {
            summaryBox.innerHTML = '';
            tableBox.innerHTML = emptyMessage(payload);
            return;
        }
        var groups = payload.groups || [];
        if (!groups.length) {
            tableBox.innerHTML = '<p class="aj-muted">' + escape(text('aj.dup.none')) + '</p>';
            return;
        }

        var head = '<table class="aj-table"><thead><tr>'
            + th('aj.col.dupfiles', ' aj-num') + th('aj.col.unit', ' aj-num')
            + th('aj.col.reclaim', ' aj-num') + th('aj.col.certainty', '')
            + th('aj.col.members', '')
            + '</tr></thead><tbody>';

        var body = groups.map(function (group) {
            var members = (group.members || []).map(function (row) {
                return '<li>' + escape(row.spaceKey || text('aj.col.nospace')) + ' / '
                    + '<a href="' + escape(contentUrl(row.containerId)) + '">'
                    + escape(row.containerTitle || row.containerId) + '</a> / '
                    + escape(row.fileName) + '</li>';
            }).join('');
            return '<tr>'
                + num(number(group.fileCount))
                + num(bytes(group.unitBytes))
                + '<td class="aj-num aj-warn">' + escape(bytes(group.reclaimableBytes))
                + '</td>'
                + '<td>' + (group.exact
                    ? '<span class="aj-badge aj-badge-duplicate">'
                        + escape(text('aj.dup.exact')) + '</span>'
                    : '<span class="aj-badge aj-badge-maybe">'
                        + escape(text('aj.dup.maybe')) + '</span>') + '</td>'
                + '<td><ul class="aj-ref-list">' + members + '</ul></td>'
                + '</tr>';
        }).join('');

        tableBox.innerHTML = head + body + '</tbody></table>'
            + '<p class="aj-foot">' + escape(text('aj.dup.note')) + '</p>';
        if (csvLink) {
            csvLink.setAttribute('href', api + '/duplicates.csv');
        }
    }

    /* ------------------------------------------------------------- 설정 화면 */

    var SETTING_FIELDS = [
        ['oldVersionCount', 'aj.set.oldversioncount', false],
        ['oldVersionBytes', 'aj.set.oldversionbytes', true],
        ['largeBytes', 'aj.set.largebytes', true],
        ['staleDays', 'aj.set.staledays', false],
        ['duplicateByteBudget', 'aj.set.hashbudget', true],
        ['keepRuns', 'aj.set.keepruns', false]
    ];

    function renderSettings(settings) {
        var html = '<table class="aj-table aj-settings"><tbody>';
        SETTING_FIELDS.forEach(function (field) {
            var value = field[2] ? Math.round(settings[field[0]] / 1048576) : settings[field[0]];
            html += '<tr><th>' + escape(text(field[1])) + '</th>'
                + '<td><input type="number" min="0" id="aj-s-' + field[0] + '" value="'
                + escape(value) + '"></td>'
                + '<td class="aj-meta">' + escape(text(field[1] + '.hint')) + '</td></tr>';
        });
        html += '<tr><th>' + escape(text('aj.set.scanhistory')) + '</th>'
            + '<td><input type="checkbox" id="aj-s-scanHistory"'
            + (settings.scanHistory ? ' checked' : '') + '></td>'
            + '<td class="aj-meta">' + escape(text('aj.set.scanhistory.hint')) + '</td></tr>'
            + '<tr><th>' + escape(text('aj.set.dupmode')) + '</th>'
            + '<td><select id="aj-s-duplicateMode">'
            + '<option value="QUICK"' + (settings.duplicateMode === 'QUICK' ? ' selected' : '')
            + '>' + escape(text('aj.set.dupmode.quick')) + '</option>'
            + '<option value="FULL"' + (settings.duplicateMode === 'FULL' ? ' selected' : '')
            + '>' + escape(text('aj.set.dupmode.full')) + '</option>'
            + '</select></td>'
            + '<td class="aj-meta">' + escape(text('aj.set.dupmode.hint')) + '</td></tr>'
            + '</tbody></table>'
            + '<div class="aj-bar aj-set-actions">'
            + '<button type="button" class="aui-button aui-button-primary"'
            + ' id="aj-save">' + escape(text('aj.action.save')) + '</button>'
            + '<span class="aj-asof" id="aj-saved"></span></div>'
            + '<p class="aj-foot">' + escape(text('aj.set.note')) + '</p>';

        tableBox.innerHTML = html;
        document.getElementById('aj-save').addEventListener('click', saveSettings);
    }

    function saveSettings() {
        var body = {
            scanHistory: document.getElementById('aj-s-scanHistory').checked,
            duplicateMode: document.getElementById('aj-s-duplicateMode').value
        };
        SETTING_FIELDS.forEach(function (field) {
            var element = document.getElementById('aj-s-' + field[0]);
            var value = element ? (parseInt(element.value, 10) || 0) : 0;
            body[field[0]] = field[2] ? value * 1048576 : value;
        });
        request('PUT', '/settings', function (status, payload) {
            if (status !== 200 || !payload) {
                banner('error', text('aj.error.network'));
                return;
            }
            /* 저장한 값이 아니라 서버가 돌려준 값을 다시 그린다 — 상·하한에 걸려 조정된
               값을 관리자가 보아야 한다. */
            renderSettings(payload);
            document.getElementById('aj-saved').textContent = text('aj.set.saved');
        }, JSON.stringify(body));
    }

    /* -------------------------------------------------------- 설명서 목차 */

    function buildToc() {
        var list = document.getElementById('aj-toc');
        var body = document.querySelector('.aj-help-body');
        if (!list || !body) {
            return;
        }
        var html = '';
        Array.prototype.forEach.call(body.querySelectorAll('h2'), function (heading, index) {
            if (!heading.id) {
                heading.id = 'aj-h-' + index;
            }
            html += '<li><a href="#' + escape(heading.id) + '">'
                + escape(heading.textContent) + '</a></li>';
        });
        list.innerHTML = html;
    }

    /* ------------------------------------------------------------------ 적재 */

    function load() {
        if (screen === 'SETTINGS') {
            request('GET', '/settings', function (status, payload) {
                if (status !== 200 || !payload) {
                    banner('error', text('aj.error.network'));
                    return;
                }
                renderSettings(payload);
            });
            return;
        }

        var path = screen === 'DUPLICATES' ? '/duplicates'
            : (screen === 'SPACE_DETAIL' ? '/space?key=' + encodeURIComponent(spaceKey)
                : '/spaces');

        request('GET', path, function (status, payload) {
            if (status !== 200 || !payload) {
                banner('error', text('aj.error.network'));
                return;
            }
            /* 새 스캔이 저장되면 표가 다시 진실을 말한다. 우리 기억은 버린다. */
            var runId = payload.shown ? payload.shown.finishedAt : null;
            if (cleanedForRun !== null && cleanedForRun !== runId) {
                cleaned = {};
            }
            cleanedForRun = runId;

            lastPayload = payload;
            renderBanners(payload);
            renderAsOf(payload.shown);

            if (screen === 'DUPLICATES') {
                renderDuplicates(payload);
            } else if (screen === 'SPACE_DETAIL') {
                renderDetail(payload);
            } else {
                renderSpaces(payload);
            }

            if (payload.progress && payload.progress.running) {
                busyButtons();
                pollTimer = window.setTimeout(load, 1500);
            } else {
                stopPolling();
                idleButtons();
            }
        });
    }

    if (scanButton) {
        scanButton.addEventListener('click', function () {
            busyButtons();
            banner('', '');
            request('POST', '/scan', function (status) {
                if (status === 409) {
                    banner('warn', text('aj.error.busy'));
                }
                load();
            });
        });
    }
    if (cancelButton) {
        cancelButton.addEventListener('click', function () {
            cancelButton.disabled = true;
            request('DELETE', '/scan', function () {
                cancelButton.disabled = false;
                load();
            });
        });
    }

    if (screen === 'HELP') {
        buildToc();
    } else {
        load();
    }
}());
