# `create` 입력 문법

`python3 scripts/hwp.py create 결과.hwpx --from 입력.md` (또는 `.json`).
출력 확장자가 `.hwpx`면 HWPX, `.hwp`면 HWP 5.0 이진 문서가 된다. 두 형식 모두 같은 입력을 받는다.

## 목차
- [마크다운](#마크다운)
- [JSON](#json)
- [기본값](#기본값)

## 마크다운

| 쓰는 법 | 결과 |
|---|---|
| `# 제목` … `###### 제목` | 굵은 제목. 1단계 16pt, 2단계 14pt, 3단계 12pt, 4단계 11pt, 5·6단계 10pt. 위아래 간격, 다음 문단과 같은 쪽 유지 |
| `# <center>제목</center>` | 가운데(또는 `<right>`) 정렬 제목 |
| 일반 줄 | 문단 하나. **한 줄이 한 문단**이다(줄을 이어 붙이지 않는다). 빈 줄은 무시 |
| `**굵게**` `__굵게__` | 굵게 |
| `*기울임*` `_기울임_` | 기울임 |
| `***굵은 기울임***` | 굵게+기울임 |
| `~~취소선~~` | 취소선 |
| `<u>밑줄</u>` | 밑줄 |
| `` `코드` `` | 그냥 글자(서식 없음) |
| `[글](https://주소)` | `글(https://주소)` 로 들어감 (하이퍼링크는 만들지 않음) |
| `<br>` | 문단 안 줄 나눔 |
| `- 항목` `* 항목` `+ 항목` | `• 항목`, 왼쪽 여백 1단계. 두 칸 들여쓰면 `◦` 2단계, 그다음 `▪`, `-`, `·` |
| `1. 항목` `1) 항목` | 번호를 적힌 그대로 두고 왼쪽 여백 1단계 |
| `> 인용` | 왼쪽 여백 1단계 |
| `<center>글</center>` `<right>글</right>` `<left>글</left>` | 문단 정렬 |
| `<p align="center">글</p>` | 문단 정렬 (center/right/left/justify) |
| GFM 표 (`\| a \| b \|` + `\|---\|---\|`) | 표. 첫 줄은 머리글: 굵게, 가운데, 회색 음영. 구분선의 `:--:`/`--:`는 열 정렬 |
| `\|` (표 안) | 글자 `\|` |
| `<!-- pagebreak -->` `\newpage` `\pagebreak` | 다음 문단부터 새 쪽 |
| `---` `***` `___` | 빈 문단 하나 |
| ```` ``` ```` 코드 블록 | 안쪽 줄을 서식 없이 한 줄씩 문단으로 |
| `\*` 같은 백슬래시 | 다음 글자를 그대로 |

열 너비는 각 열의 가장 긴 글자 수에 비례해 자동으로 정한다(너무 좁아지지 않게 최소값 있음). 정확히 정하려면 JSON의 `col_widths`를 쓴다.

마크다운 표로는 셀 병합을 할 수 없다. 병합이 필요하면 JSON을 쓴다.

## JSON

```json
{
  "page": {
    "orientation": "landscape",
    "margins_mm": {"left": 20, "right": 20, "top": 15, "bottom": 15, "header": 15, "footer": 15}
  },
  "font": "맑은 고딕",
  "size": 11,
  "blocks": [
    {"type": "heading", "level": 1, "text": "견적서"},

    {"type": "paragraph", "text": "마크다운 **강조**도 그대로 됩니다"},

    {"type": "paragraph", "align": "center", "indent": 1,
     "runs": [
       {"text": "빨간 굵은 글", "bold": true, "color": "#C00000", "size": 14},
       {"text": " 보통 글 "},
       {"text": "취소선", "strike": true},
       {"text": " H"}, {"text": "2", "subscript": true}, {"text": "O"},
       {"text": " 다른 글꼴", "font": "돋움"}
     ]},

    {"type": "table", "header": true, "col_widths": [1, 3, 1],
     "rows": [
       ["품목", "설명", "금액"],
       ["가", "첫 줄\n둘째 줄", {"text": "1,000", "align": "right"}],
       ["합계", "", {"text": "1,000", "align": "right", "bold": true}]
     ],
     "merges": [{"row": 2, "col": 0, "rowspan": 1, "colspan": 2}]},

    {"type": "page_break"},

    {"type": "paragraph", "text": "둘째 쪽"}
  ]
}
```

블록 종류:

| type | 필드 |
|---|---|
| `heading` | `level`(1–6), `text` 또는 `runs`, `align` |
| `paragraph` | `text`(마크다운 강조 해석) 또는 `runs`, `align`(left/center/right/justify/distribute), `indent`(왼쪽 여백 단계, 1단계 = 20pt) |
| `table` | `rows`(2차원 배열; 셀은 문자열 또는 `{"text", "align", "bold", "color", "size", ...}`), `header`(첫 줄을 머리글로, 기본 true), `col_widths`(비율), `merges`(`row`,`col`,`rowspan`,`colspan`; 가려지는 칸의 값은 무시) |
| `page_break` | 다음 블록부터 새 쪽 |

`runs`의 글자 속성: `bold`, `italic`, `underline`, `strike`, `superscript`, `subscript`, `size`(pt), `color`(`#RRGGBB`), `font`(글꼴 이름).

셀 글에 `\n`이 있으면 셀 안에서 문단이 나뉜다.

## 기본값

- 용지: A4 세로, 여백 왼쪽·오른쪽 30mm, 위 20mm, 아래 15mm, 머리말·꼬리말 15mm (한글 새 문서 기본값과 같음)
- 글꼴: 함초롬바탕 10pt, 줄 간격 160%, 양쪽 정렬, 어절 단위 줄 나눔 (한글 "바탕글" 스타일)
- 표: 0.12mm 실선 테두리, 셀 안쪽 여백 좌우 1.8mm·위아래 0.5mm, 본문 폭에 맞춤
- `font`에 컴퓨터에 없는 글꼴 이름을 주면 한글이 열 때 비슷한 글꼴로 바꿔 보여 준다
