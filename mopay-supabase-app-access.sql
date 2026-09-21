-- MoPay Demo - mobile app access layer
-- Run AFTER mopay-supabase-foundation.sql.
-- Prototype only: this intentionally exposes ONLY the fixed demo records.

create or replace function public.get_demo_customer_snapshot()
returns jsonb
language sql
security definer
set search_path = public, extensions
as $$
  select jsonb_build_object(
    'customer', jsonb_build_object(
      'id', c.id,
      'full_name', c.full_name,
      'phone_number', c.phone_number
    ),
    'wallet', jsonb_build_object(
      'balance', w.balance,
      'currency', w.currency,
      'is_active', w.is_active
    ),
    'card', jsonb_build_object(
      'card_token', card.card_token,
      'masked_number', card.masked_number,
      'status', card.status,
      'last_used_at', card.last_used_at
    ),
    'transactions', coalesce((
      select jsonb_agg(x.item order by x.created_at desc)
      from (
        select
          t.created_at,
          jsonb_build_object(
            'reference', t.reference,
            'amount', t.amount,
            'currency', t.currency,
            'status', t.status,
            'reason', t.reason,
            'balance_before', t.balance_before,
            'balance_after', t.balance_after,
            'merchant_name', m.business_name,
            'created_at', t.created_at
          ) as item
        from public.transactions t
        join public.merchants m on m.id = t.merchant_id
        where t.customer_id = c.id
        order by t.created_at desc
        limit 10
      ) x
    ), '[]'::jsonb)
  )
  from public.customers c
  join public.wallets w on w.customer_id = c.id
  join public.cards card on card.customer_id = c.id
  where c.id = '11111111-1111-1111-1111-111111111111'::uuid
  limit 1;
$$;

create or replace function public.get_demo_merchant_dashboard()
returns jsonb
language sql
security definer
set search_path = public, extensions
as $$
  select jsonb_build_object(
    'merchant', jsonb_build_object(
      'id', m.id,
      'business_name', m.business_name,
      'phone_number', m.phone_number,
      'is_active', m.is_active
    ),
    'today', jsonb_build_object(
      'transaction_count', count(t.id),
      'successful_count', count(t.id) filter (where t.status = 'successful'),
      'failed_count', count(t.id) filter (where t.status = 'declined'),
      'total_amount', coalesce(sum(t.amount) filter (where t.status = 'successful'), 0)
    ),
    'transactions', coalesce(jsonb_agg(
      jsonb_build_object(
        'reference', t.reference,
        'amount', t.amount,
        'currency', t.currency,
        'status', t.status,
        'reason', t.reason,
        'card_number', c.masked_number,
        'created_at', t.created_at
      ) order by t.created_at desc
    ) filter (where t.id is not null), '[]'::jsonb)
  )
  from public.merchants m
  left join public.transactions t
    on t.merchant_id = m.id
   and t.created_at >= date_trunc('day', now())
  left join public.cards c on c.id = t.card_id
  where m.id = '22222222-2222-2222-2222-222222222222'::uuid
  group by m.id, m.business_name, m.phone_number, m.is_active;
$$;

-- Replace the demo payment RPC so declined attempts are also visible in history.
create or replace function public.process_demo_payment(
  p_card_token text,
  p_merchant_id uuid,
  p_amount bigint,
  p_pin text
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_card public.cards%rowtype;
  v_wallet public.wallets%rowtype;
  v_reference text;
  v_balance_before bigint;
  v_balance_after bigint;
  v_transaction_id uuid;
begin
  if p_amount is null or p_amount <= 0 then
    return jsonb_build_object('success', false, 'reason', 'INVALID_AMOUNT');
  end if;

  if not exists (
    select 1 from public.merchants
    where id = p_merchant_id and is_active = true
  ) then
    return jsonb_build_object('success', false, 'reason', 'MERCHANT_NOT_AVAILABLE');
  end if;

  select * into v_card
  from public.cards
  where card_token = p_card_token
  for update;

  if not found then
    return jsonb_build_object('success', false, 'reason', 'CARD_NOT_FOUND');
  end if;

  v_reference := 'MP-' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 12));

  if v_card.status <> 'active' then
    insert into public.transactions (
      reference, card_id, customer_id, merchant_id, amount, currency,
      status, reason
    ) values (
      v_reference, v_card.id, v_card.customer_id, p_merchant_id, p_amount,
      'XAF', 'declined', 'CARD_' || upper(v_card.status::text)
    );
    return jsonb_build_object('success', false, 'reason', 'CARD_' || upper(v_card.status::text));
  end if;

  if not crypt(coalesce(p_pin, ''), v_card.pin_hash) = v_card.pin_hash then
    update public.cards
    set failed_pin_attempts = failed_pin_attempts + 1,
        updated_at = now()
    where id = v_card.id;

    insert into public.security_events(card_id, customer_id, event_type, details)
    values (
      v_card.id, v_card.customer_id, 'invalid_pin',
      jsonb_build_object('merchant_id', p_merchant_id)
    );

    insert into public.transactions (
      reference, card_id, customer_id, merchant_id, amount, currency,
      status, reason
    ) values (
      v_reference, v_card.id, v_card.customer_id, p_merchant_id, p_amount,
      'XAF', 'declined', 'INVALID_PIN'
    );

    return jsonb_build_object('success', false, 'reason', 'INVALID_PIN');
  end if;

  select * into v_wallet
  from public.wallets
  where customer_id = v_card.customer_id
  for update;

  if not found or not v_wallet.is_active then
    insert into public.transactions (
      reference, card_id, customer_id, merchant_id, amount, currency,
      status, reason
    ) values (
      v_reference, v_card.id, v_card.customer_id, p_merchant_id, p_amount,
      'XAF', 'declined', 'WALLET_NOT_AVAILABLE'
    );
    return jsonb_build_object('success', false, 'reason', 'WALLET_NOT_AVAILABLE');
  end if;

  v_balance_before := v_wallet.balance;

  if v_balance_before < p_amount then
    insert into public.transactions (
      reference, card_id, customer_id, merchant_id, amount, currency,
      status, reason, balance_before, balance_after
    ) values (
      v_reference, v_card.id, v_card.customer_id, p_merchant_id, p_amount,
      v_wallet.currency, 'declined', 'INSUFFICIENT_FUNDS',
      v_balance_before, v_balance_before
    );
    return jsonb_build_object('success', false, 'reason', 'INSUFFICIENT_FUNDS');
  end if;

  v_balance_after := v_balance_before - p_amount;

  update public.wallets
  set balance = v_balance_after, updated_at = now()
  where id = v_wallet.id;

  update public.cards
  set failed_pin_attempts = 0, last_used_at = now(), updated_at = now()
  where id = v_card.id;

  insert into public.transactions (
    reference, card_id, customer_id, merchant_id, amount, currency,
    status, balance_before, balance_after
  ) values (
    v_reference, v_card.id, v_card.customer_id, p_merchant_id,
    p_amount, v_wallet.currency, 'successful',
    v_balance_before, v_balance_after
  ) returning id into v_transaction_id;

  insert into public.security_events(card_id, customer_id, event_type, details)
  values (
    v_card.id, v_card.customer_id, 'payment_success',
    jsonb_build_object(
      'merchant_id', p_merchant_id,
      'transaction_id', v_transaction_id,
      'amount', p_amount
    )
  );

  return jsonb_build_object(
    'success', true,
    'reference', v_reference,
    'amount', p_amount,
    'currency', v_wallet.currency,
    'balance_before', v_balance_before,
    'balance_after', v_balance_after
  );
end;
$$;

-- The mobile prototype uses the publishable key, which maps to the anon role.
-- These grants expose only the RPC entry points, not the underlying tables.
grant execute on function public.get_demo_customer_snapshot() to anon, authenticated;
grant execute on function public.get_demo_merchant_dashboard() to anon, authenticated;
grant execute on function public.process_demo_payment(text, uuid, bigint, text) to anon, authenticated;
grant execute on function public.set_demo_card_status(text, public.card_status) to anon, authenticated;

-- IMPORTANT: this file is for the demo only. Before production:
-- 1) add Supabase Auth users;
-- 2) replace fixed demo IDs with auth_user_id checks;
-- 3) keep payment execution behind a server-side/Edge Function authorization layer;
-- 4) integrate approved MTN/Orange APIs instead of the demo wallet debit.

-- Demo-only customer actions used by the Customer APK.
create or replace function public.change_demo_pin(
  p_card_token text,
  p_old_pin text,
  p_new_pin text
)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_card public.cards%rowtype;
begin
  if length(coalesce(p_new_pin, '')) <> 4 or p_new_pin !~ '^[0-9]{4}$' then
    return jsonb_build_object('success', false, 'reason', 'INVALID_NEW_PIN');
  end if;

  select * into v_card from public.cards where card_token = p_card_token for update;
  if not found then return jsonb_build_object('success', false, 'reason', 'CARD_NOT_FOUND'); end if;

  if not crypt(coalesce(p_old_pin, ''), v_card.pin_hash) = v_card.pin_hash then
    insert into public.security_events(card_id, customer_id, event_type, details)
    values (v_card.id, v_card.customer_id, 'invalid_pin_change', '{}'::jsonb);
    return jsonb_build_object('success', false, 'reason', 'INVALID_PIN');
  end if;

  update public.cards
  set pin_hash = crypt(p_new_pin, gen_salt('bf')), failed_pin_attempts = 0, updated_at = now()
  where id = v_card.id;

  insert into public.security_events(card_id, customer_id, event_type, details)
  values (v_card.id, v_card.customer_id, 'pin_changed', '{}'::jsonb);

  return jsonb_build_object('success', true);
end;
$$;

grant execute on function public.change_demo_pin(text, text, text) to anon, authenticated;

create or replace function public.demo_top_up(p_amount bigint)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_wallet public.wallets%rowtype;
  v_after bigint;
begin
  if p_amount is null or p_amount <= 0 or p_amount > 1000000 then
    return jsonb_build_object('success', false, 'reason', 'INVALID_AMOUNT');
  end if;
  select * into v_wallet from public.wallets where customer_id = '11111111-1111-1111-1111-111111111111'::uuid for update;
  if not found or not v_wallet.is_active then return jsonb_build_object('success', false, 'reason', 'WALLET_NOT_AVAILABLE'); end if;
  v_after := v_wallet.balance + p_amount;
  update public.wallets set balance = v_after, updated_at = now() where id = v_wallet.id;
  return jsonb_build_object('success', true, 'balance_after', v_after, 'amount', p_amount);
end;
$$;

grant execute on function public.demo_top_up(bigint) to anon, authenticated;

create or replace function public.demo_send(p_amount bigint, p_recipient text)
returns jsonb
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_wallet public.wallets%rowtype;
  v_after bigint;
begin
  if p_amount is null or p_amount <= 0 or p_amount > 1000000 or length(trim(coalesce(p_recipient, ''))) < 3 then
    return jsonb_build_object('success', false, 'reason', 'INVALID_TRANSFER');
  end if;
  select * into v_wallet from public.wallets where customer_id = '11111111-1111-1111-1111-111111111111'::uuid for update;
  if not found or not v_wallet.is_active then return jsonb_build_object('success', false, 'reason', 'WALLET_NOT_AVAILABLE'); end if;
  if v_wallet.balance < p_amount then return jsonb_build_object('success', false, 'reason', 'INSUFFICIENT_FUNDS'); end if;
  v_after := v_wallet.balance - p_amount;
  update public.wallets set balance = v_after, updated_at = now() where id = v_wallet.id;
  insert into public.security_events(card_id, customer_id, event_type, details)
  select id, customer_id, 'demo_send', jsonb_build_object('amount', p_amount, 'recipient', p_recipient)
  from public.cards where card_token = 'CARD_DEMO_4821';
  return jsonb_build_object('success', true, 'balance_after', v_after, 'amount', p_amount, 'recipient', p_recipient);
end;
$$;

grant execute on function public.demo_send(bigint, text) to anon, authenticated;
