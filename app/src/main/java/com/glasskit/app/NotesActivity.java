package com.glasskit.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Peringatan (senarai tugasan dengan kotak semak & masa) dan Nota. */
public class NotesActivity extends Activity {
    static final String EXTRA_TAB = "tab", EXTRA_NOTE = "noteId", EXTRA_NEW = "new";

    private int tab;
    private LinearLayout list, tabs;
    private EditText input;
    private long pendingDue;
    private boolean pendingImp;
    private TextView dueChip, starChip;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF07080F);
        getWindow().setNavigationBarColor(0xFF0B1424);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setFitsSystemWindows(true);
        box.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF07080F, 0xFF15102A, 0xFF0B1424}));
        box.setPadding(dp(18), dp(20), dp(18), dp(10));
        setContentView(box);

        tabs = new LinearLayout(this);
        tabs.setBackground(Ui.glass(this, 22, 26));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        int[] labels = {R.string.t_tab_tasks, R.string.t_tab_notes};
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            TextView t = Ui.text(this, 15, 650, Color.WHITE);
            t.setText(labels[i]);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> {
                tab = idx;
                rebuild();
            });
            tabs.addView(t, new LinearLayout.LayoutParams(0, dp(40), 1f));
        }
        box.addView(tabs, new LinearLayout.LayoutParams(-1, -2));

        // baris tambah
        LinearLayout add = new LinearLayout(this);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(0, dp(14), 0, dp(6));
        input = new EditText(this);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.argb(120, 235, 235, 245));
        input.setBackground(Ui.glass(this, 16, 30));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((v, a, e) -> {
            submit();
            return true;
        });
        add.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView plus = Ui.text(this, 22, 600, Color.BLACK);
        plus.setText("+");
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(Ui.round(Color.WHITE, dp(22)));
        plus.setOnClickListener(v -> submit());
        Ui.press(plus);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(44), dp(44));
        pl.leftMargin = dp(10);
        add.addView(plus, pl);
        box.addView(add);

        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, 0, 0, dp(8));
        dueChip = chip(getString(R.string.t_set_time), v -> pickDue(null));
        starChip = chip("★ " + getString(R.string.t_important), v -> {
            pendingImp = !pendingImp;
            paintChips();
        });
        chips.addView(dueChip);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.leftMargin = dp(8);
        chips.addView(starChip, sl);
        box.addView(chips);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        tab = i.getIntExtra(EXTRA_TAB, tab);
        rebuild();
        long nid = i.getLongExtra(EXTRA_NOTE, 0);
        if (nid != 0) editNote(Notes.note(this, nid));
        else if (i.getBooleanExtra(EXTRA_NEW, false)) {
            if (tab == 1) editNote(null);
            else {
                input.requestFocus();
                getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
        }
    }

    private TextView chip(String s, View.OnClickListener l) {
        TextView t = Ui.text(this, 13, 600, Color.WHITE);
        t.setText(s);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(Ui.glass(this, 16, 24));
        t.setOnClickListener(l);
        Ui.press(t);
        return t;
    }

    private void paintChips() {
        dueChip.setText(pendingDue > 0 ? "⏰ " + dueText(pendingDue) : getString(R.string.t_set_time));
        dueChip.setTextColor(pendingDue > 0 ? 0xFFFFD60A : Color.WHITE);
        starChip.setTextColor(pendingImp ? 0xFFFF9F0A : Color.WHITE);
    }

    private String dueText(long t) {
        boolean is24 = DateFormat.is24HourFormat(this);
        Calendar a = Calendar.getInstance(), k = Calendar.getInstance();
        k.setTimeInMillis(t);
        String tm = new SimpleDateFormat(is24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date(t));
        if (a.get(Calendar.DAY_OF_YEAR) == k.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == k.get(Calendar.YEAR)) {
            return getString(R.string.today) + " " + tm;
        }
        return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(t)) + " " + tm;
    }

    private void rebuild() {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            TextView t = (TextView) tabs.getChildAt(i);
            t.setBackground(i == tab ? Ui.round(Color.WHITE, dp(20)) : null);
            t.setTextColor(i == tab ? 0xFF0B0B10 : Color.WHITE);
        }
        input.setHint(tab == 0 ? R.string.t_new_task : R.string.t_new_note);
        ((View) dueChip.getParent()).setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        paintChips();
        list.removeAllViews();
        if (tab == 0) buildTasks();
        else buildNotes();
    }

    private void submit() {
        String s = input.getText().toString().trim();
        if (s.isEmpty()) {
            if (tab == 1) editNote(null);
            return;
        }
        if (tab == 0) {
            Notes.addTask(this, s, pendingDue, pendingImp);
            pendingDue = 0;
            pendingImp = false;
        } else {
            Notes.Note n = new Notes.Note();
            n.id = System.currentTimeMillis();
            n.text = s;
            n.updated = n.id;
            Notes.putNote(this, n);
        }
        input.setText("");
        rebuild();
    }

    // ======================= Tugasan =======================

    private void buildTasks() {
        List<Notes.Task> ts = Notes.tasks(this);
        if (ts.isEmpty()) {
            empty(R.string.t_empty_tasks);
            return;
        }
        for (Notes.Task t : ts) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(12), dp(14), dp(12));
            row.setBackground(Ui.glass(this, 18, 20));
            FrameLayout box = new FrameLayout(this);
            box.setBackground(t.done ? Ui.round(0xFF30D158, dp(13)) : ring());
            if (t.done) {
                TextView ck = Ui.text(this, 15, 800, Color.WHITE);
                ck.setText("✓");
                ck.setGravity(Gravity.CENTER);
                box.addView(ck, new FrameLayout.LayoutParams(-1, -1));
            }
            box.setOnClickListener(v -> {
                Notes.setDone(this, t.id, !t.done);
                rebuild();
            });
            row.addView(box, new LinearLayout.LayoutParams(dp(26), dp(26)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, dp(8), 0);
            TextView tx = Ui.text(this, 15, 550, t.done ? Color.argb(120, 235, 235, 245) : Color.WHITE);
            tx.setText(t.text);
            if (t.done) tx.setPaintFlags(tx.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            col.addView(tx);
            if (t.due > 0) {
                TextView du = Ui.text(this, 12, 600, t.due < System.currentTimeMillis() && !t.done ? 0xFFFF453A : 0xFFFFD60A);
                du.setText("⏰ " + dueText(t.due));
                col.addView(du);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            if (t.important) {
                TextView st = Ui.text(this, 16, 700, 0xFFFF9F0A);
                st.setText("★");
                row.addView(st);
            }
            row.setOnClickListener(v -> editTask(t));
            row.setOnLongClickListener(v -> {
                confirmDelete(() -> {
                    Notes.deleteTask(this, t.id);
                    rebuild();
                });
                return true;
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.bottomMargin = dp(8);
            list.addView(row, rl);
        }
    }

    private GradientDrawable ring() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(2), Color.argb(200, 235, 235, 245));
        return g;
    }

    private void editTask(Notes.Task t) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(8), dp(20), 0);
        EditText e = new EditText(this);
        e.setText(t.text);
        v.addView(e);
        final long[] due = {t.due};
        final boolean[] imp = {t.important};
        TextView d = Ui.text(this, 14, 600, 0xFF0A84FF);
        d.setPadding(0, dp(12), 0, dp(4));
        d.setText(due[0] > 0 ? "⏰ " + dueText(due[0]) : getString(R.string.t_set_time));
        d.setOnClickListener(x -> pickDue(ms -> {
            due[0] = ms;
            d.setText(ms > 0 ? "⏰ " + dueText(ms) : getString(R.string.t_set_time));
        }));
        v.addView(d);
        TextView s = Ui.text(this, 14, 600, 0xFFFF9F0A);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setText((imp[0] ? "★ " : "☆ ") + getString(R.string.t_important));
        s.setOnClickListener(x -> {
            imp[0] = !imp[0];
            s.setText((imp[0] ? "★ " : "☆ ") + getString(R.string.t_important));
        });
        v.addView(s);
        new AlertDialog.Builder(this)
                .setTitle(R.string.t_edit)
                .setView(v)
                .setPositiveButton(android.R.string.ok, (dl, w) -> {
                    String nt = e.getText().toString().trim();
                    if (nt.isEmpty()) return;
                    t.text = nt;
                    t.due = due[0];
                    t.important = imp[0];
                    Notes.updateTask(this, t);
                    rebuild();
                })
                .setNeutralButton(R.string.t_delete, (dl, w) -> {
                    Notes.deleteTask(this, t.id);
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    interface DueCb { void on(long ms); }

    private void pickDue(DueCb cb) {
        Calendar k = Calendar.getInstance();
        k.add(Calendar.HOUR_OF_DAY, 1);
        k.set(Calendar.MINUTE, 0);
        new DatePickerDialog(this, (dp, y, m, d) -> {
            Calendar sel = Calendar.getInstance();
            sel.set(y, m, d);
            new TimePickerDialog(this, (tp, hh, mm) -> {
                sel.set(Calendar.HOUR_OF_DAY, hh);
                sel.set(Calendar.MINUTE, mm);
                sel.set(Calendar.SECOND, 0);
                sel.set(Calendar.MILLISECOND, 0);
                long ms = sel.getTimeInMillis();
                if (cb != null) cb.on(ms);
                else {
                    pendingDue = ms;
                    paintChips();
                }
            }, k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE), DateFormat.is24HourFormat(this)).show();
        }, k.get(Calendar.YEAR), k.get(Calendar.MONTH), k.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ======================= Nota =======================

    private void buildNotes() {
        List<Notes.Note> ns = Notes.notes(this);
        if (ns.isEmpty()) {
            empty(R.string.t_empty_notes);
            return;
        }
        for (Notes.Note n : ns) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.setBackground(Ui.glass(this, 18, 20));
            TextView t = Ui.line(this, 16, 700, Color.WHITE);
            t.setText((n.pinned ? "📌 " : "") + Notes.firstLine(n.text));
            card.addView(t);
            String rest = Notes.rest(n.text);
            if (!rest.isEmpty()) {
                TextView r = Ui.text(this, 13, 400, Color.argb(170, 235, 235, 245));
                r.setMaxLines(2);
                r.setEllipsize(android.text.TextUtils.TruncateAt.END);
                r.setText(rest);
                card.addView(r);
            }
            card.setOnClickListener(v -> editNote(n));
            card.setOnLongClickListener(v -> {
                confirmDelete(() -> {
                    Notes.deleteNote(this, n.id);
                    rebuild();
                });
                return true;
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.bottomMargin = dp(8);
            list.addView(card, cl);
        }
    }

    private void editNote(Notes.Note n) {
        final Notes.Note note;
        if (n == null) {
            note = new Notes.Note();
            note.id = System.currentTimeMillis();
            note.text = "";
        } else {
            note = n;
        }
        EditText e = new EditText(this);
        e.setText(note.text);
        e.setMinLines(6);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(n == null ? R.string.t_new_note : R.string.t_edit)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, (dl, w) -> {
                    note.text = e.getText().toString();
                    note.updated = System.currentTimeMillis();
                    Notes.putNote(this, note);
                    rebuild();
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (n != null) {
            b.setNeutralButton(note.pinned ? R.string.t_unpin : R.string.t_pin, (dl, w) -> {
                note.text = e.getText().toString();
                note.pinned = !note.pinned;
                note.updated = System.currentTimeMillis();
                Notes.putNote(this, note);
                rebuild();
            });
        }
        b.show();
    }

    private void empty(int res) {
        TextView t = Ui.text(this, 14, 500, Color.argb(150, 235, 235, 245));
        t.setText(res);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(40), 0, 0);
        list.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private void confirmDelete(Runnable r) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.t_delete_q)
                .setPositiveButton(R.string.t_delete, (d, w) -> r.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
