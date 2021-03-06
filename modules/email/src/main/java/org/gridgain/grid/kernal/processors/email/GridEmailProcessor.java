/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gridgain.grid.kernal.processors.email;

import org.apache.ignite.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.thread.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.worker.*;

import javax.mail.*;
import javax.mail.internet.*;
import java.util.*;

/**
 * Email (SMTP) processor. Responsible for sending emails.
 */
public class GridEmailProcessor extends GridEmailProcessorAdapter {
    /** Maximum emails queue size. */
    public static final int QUEUE_SIZE = 1024;

    /** */
    @SuppressWarnings({"FieldAccessedSynchronizedAndUnsynchronized"})
    private Deque<GridEmailHolder> q;

    /** */
    private IgniteThread snd;

    /** */
    private GridWorker worker;

    /** */
    private final boolean isSmtpEnabled;

    /**
     * @param ctx Kernal context.
     */
    public GridEmailProcessor(GridKernalContext ctx) {
        super(ctx);

        isSmtpEnabled = ctx.config().getSmtpHost() != null;

        if (isSmtpEnabled) {
            worker = new GridWorker(ctx.config().getGridName(), "email-sender-worker", log) {
                @SuppressWarnings({"SynchronizeOnNonFinalField"})
                @Override protected void body() throws InterruptedException {
                    while (!Thread.currentThread().isInterrupted())
                        synchronized (q) {
                            while (q.isEmpty())
                                q.wait();

                            GridEmailHolder email = q.removeFirst();

                            assert email != null;

                            try {
                                sendNow(email.subject(), email.body(), email.html(), email.addresses());

                                email.future().onDone(true);
                            }
                            catch (IgniteCheckedException e) {
                                U.error(log, "Failed to send email with subject: " + email.subject(), e);

                                email.future().onDone(e);
                            }
                        }
                }
            };
        }
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        if (isSmtpEnabled) {
            assert q == null;
            assert snd == null;

            q = new LinkedList<>();

            snd = new IgniteThread(ctx.config().getGridName(), "email-sender-thread", worker);

            snd.start();
        }

        if (log.isDebugEnabled())
            log.debug("Started email processor" + (isSmtpEnabled ? "." : " (inactive)."));
    }

    /** {@inheritDoc} */
    @Override public void stop(boolean cancel) throws IgniteCheckedException {
        if (isSmtpEnabled) {
            U.interrupt(snd);
            U.join(snd, log);

            snd = null;

            if (q != null) {
                if (!q.isEmpty())
                    U.warn(log, "Emails queue is not empty on email processor stop.");

                q.clear();

                q = null;
            }
        }

        if (log.isDebugEnabled())
            log.debug("Stopped email processor.");
    }

    /** {@inheritDoc} */
    @Override public void sendNow(String subj, String body, boolean html) throws IgniteCheckedException {
        String[] addrs = ctx.config().getAdminEmails();

        if (addrs != null && addrs.length > 0)
            sendNow(subj, body, html, Arrays.asList(addrs));
    }

    /** {@inheritDoc} */
    @Override public void sendNow(String subj, String body, boolean html, Collection<String> addrs)
        throws IgniteCheckedException {
        assert subj != null;
        assert body != null;
        assert addrs != null;
        assert !addrs.isEmpty();

        if (isSmtpEnabled) {
            IgniteConfiguration cfg = ctx.config();

            sendEmail(
                // Static SMTP configuration data.
                cfg.getSmtpHost(),
                cfg.getSmtpPort(),
                cfg.isSmtpSsl(),
                cfg.isSmtpStartTls(),
                cfg.getSmtpUsername(),
                cfg.getSmtpPassword(),
                cfg.getSmtpFromEmail(),

                // Per-email data.
                subj,
                body,
                html,
                addrs
            );
        }
    }

    /** {@inheritDoc} */
    @Override public IgniteFuture<Boolean> schedule(String subj, String body, boolean html) {
        String[] addrs = ctx.config().getAdminEmails();

        return addrs == null || addrs.length == 0 ? new GridFinishedFuture<>(ctx, false) :
            schedule(subj, body, html, Arrays.asList(addrs));
    }

    /** {@inheritDoc} */
    @SuppressWarnings({"SynchronizeOnNonFinalField"})
    @Override public IgniteFuture<Boolean> schedule(String subj, String body, boolean html, Collection<String> addrs) {
        assert subj != null;
        assert body != null;
        assert addrs != null;
        assert !addrs.isEmpty();

        if (isSmtpEnabled)
            synchronized (q) {
                if (q.size() == QUEUE_SIZE) {
                    U.warn(log, "Email '" + subj + "' failed to schedule b/c queue is full.");

                    return new GridFinishedFuture<>(ctx, false);
                }
                else {
                    GridFutureAdapter<Boolean> fut = new GridFutureAdapter<Boolean>(ctx) {
                        @SuppressWarnings({"SynchronizeOnNonFinalField"})
                        @Override public boolean cancel() {
                            synchronized (q) {
                                for (GridEmailHolder email : q)
                                    if (email.future() == this) {
                                        q.remove(email); // We accept full scan on removal here.

                                        return true;
                                    }
                            }

                            return false;
                        }
                    };

                    q.addLast(new GridEmailHolder(fut, subj, body, html, addrs));

                    q.notifyAll();

                    return fut;
                }
            }
        else
            return new GridFinishedFuture<>(ctx, false);
    }
    /**
     *
     * @param smtpHost SMTP host.
     * @param smtpPort SMTP port.
     * @param ssl SMTP SSL.
     * @param startTls Start TLS flag.
     * @param username Email authentication user name.
     * @param pwd Email authentication password.
     * @param from From email.
     * @param subj Email subject.
     * @param body Email body.
     * @param html HTML format flag.
     * @param addrs Addresses to send email to.
     * @throws IgniteCheckedException Thrown in case when sending email failed.
     */
    public static void sendEmail(String smtpHost, int smtpPort, boolean ssl, boolean startTls, final String username,
        final String pwd, String from, String subj, String body, boolean html, Collection<String> addrs)
        throws IgniteCheckedException {
        assert smtpHost != null;
        assert smtpPort > 0;
        assert from != null;
        assert subj != null;
        assert body != null;
        assert addrs != null;
        assert !addrs.isEmpty();

        Properties props = new Properties();

        props.setProperty("mail.transport.protocol", "smtp");
        props.setProperty("mail.smtp.host", smtpHost);
        props.setProperty("mail.smtp.port", Integer.toString(smtpPort));

        if (ssl)
            props.setProperty("mail.smtp.ssl", "true");

        if (startTls)
            props.setProperty("mail.smtp.starttls.enable", "true");

        Authenticator auth = null;

        // Add property for authentication by username.
        if (username != null && !username.isEmpty()) {
            props.setProperty("mail.smtp.auth", "true");

            auth = new Authenticator() {
                @Override public PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, pwd);
                }
            };
        }

        Session ses = Session.getInstance(props, auth);

        MimeMessage email = new MimeMessage(ses);

        try {
            email.setFrom(new InternetAddress(from));
            email.setSubject(subj);
            email.setSentDate(new Date());

            if (html)
                email.setText(body, "UTF-8", "html");
            else
                email.setText(body);

            Address[] rcpts = new Address[addrs.size()];

            int i = 0;

            for (String addr : addrs)
                rcpts[i++] = new InternetAddress(addr);

            email.setRecipients(MimeMessage.RecipientType.TO, rcpts);

            Transport.send(email);
        }
        catch (MessagingException e) {
            throw new IgniteCheckedException("Failed to send email.", e);
        }
    }
}
