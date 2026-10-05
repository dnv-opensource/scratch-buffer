# Joining with a member record

Create `members/<your-github-handle>.edn`:

```clojure
{:github "your-github-handle"
 :joined "2026-10"}
```

Use the month you join (`YYYY-MM`). Also contribute one small useful thing and
open a pull request. Once merged, you are a member.

Use the code block's **copy icon** in People's joining section or **M-x copy-member-template** to
copy these two fields with the current month. Replace `your-github-handle` in both
the record and filename. The visible template can also be selected and copied
manually; clipboard permission is not required to join.

Optional, explicitly opt-in fields:

```clojure
{:github "your-github-handle"
 :joined "2026-10"
 :full-name "A name you want to share"
 :emacs-since "2018"
 :favorite-command "magit-status"
 :interests #{:clojure :org-mode}
 :bio "A short public note, if you want one."}
```

`:emacs-since` is the year you started using Emacs (`YYYY`), not the month you
joined this group. An approximate year is fine. It is optional, publicly visible,
and displayed in the directory and your member page. Leave it out if you are
still exploring Emacs or would rather not share it. Years must be between 1976
and the current year.

`:full-name` is an optional public display name. When provided, it appears in the
directory, on your profile, and beside content you author; your GitHub handle
still identifies the record and its URL. Leave it out to use your handle instead.

Do not add email, title, team, office, location, or corporate-directory data.
All records are publicly visible. The site does not infer membership from
repository contributors.

Your public GitHub avatar appears in the People directory and content bylines. The build copies a
small image into the site, so visitors do not contact GitHub to load it and it
remains available offline. No photograph is required; GitHub's default avatar
works too. No other profile information is fetched.

Only add your own member record, with information you want to publish.
