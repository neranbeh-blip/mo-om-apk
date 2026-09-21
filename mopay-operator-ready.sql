-- MoPay operator-ready core
-- Run AFTER mopay-supabase-foundation.sql.
-- This implements the internal wallet/ledger flows that can be exercised before
-- MTN/Orange operator APIs are connected. Operator API settlement is the remaining
-- external dependency; do not use the public demo RPCs in production.

create extension if not exists pgcrypto;

do $$ begin
  create type public.account_kind as enum ('customer','merchant','agent');
exception when duplicate_object then null;
end $$;

do $$ begin
  create type public.transfer_status as enum ('successful','declined','reversed');
exception when duplicate_object then null;
end $$;

do $$ begin
  create type public.transfer_channel as enum ('C2M','M2M','C2A','A2C','C2C','TOPUP','CASHOUT');
exception when duplicate_object then null;
end $$;

create table if not exists public.agents (
  id uuid primary key default gen_random_uuid(),
  full_name text not null,
  phone_number text unique not null,
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.merchant_wallets (
  id uuid primary key default gen_random_uuid(),
  merchant_id uuid not null unique references public.merchants(id) on delete restrict,
  currency text not null default 'XAF',
  balance bigint not null default 0 check (balance >= 0),
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.agent_wallets (
  id uuid primary key default gen_random_uuid(),
  agent_id uuid not null unique references public.agents(id) on delete restrict,
  currency text not null default 'XAF',
  balance bigint not null default 0 check (balance >= 0),
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.wallet_transfers (
  id uuid primary key default gen_random_uuid(),
  reference text not null unique,
  channel public.transfer_channel not null,
  sender_kind public.account_kind not null,
  sender_id uuid not null,
  receiver_kind public.account_kind not null,
  receiver_id uuid not null,
  amount bigint not null check (amount > 0),
  currency text not null default 'XAF',
  status public.transfer_status not null default 'successful',
  reason text,
  sender_balance_before bigint,
  sender_balance_after bigint,
  receiver_balance_before bigint,
  receiver_balance_after bigint,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists idx_wallet_transfers_sender on public.wallet_transfers(sender_kind, sender_id, created_at desc);
create index if not exists idx_wallet_transfers_receiver on public.wallet_transfers(receiver_kind, receiver_id, created_at desc);
create index if not exists idx_wallet_transfers_reference on public.wallet_transfers(reference);

drop trigger if exists agents_updated_at on public.agents;
create trigger agents_updated_at before update on public.agents for each row execute function public.set_updated_at();
drop trigger if exists merchant_wallets_updated_at on public.merchant_wallets;
create trigger merchant_wallets_updated_at before update on public.merchant_wallets for each row execute function public.set_updated_at();
drop trigger if exists agent_wallets_updated_at on public.agent_wallets;
create trigger agent_wallets_updated_at before update on public.agent_wallets for each row execute function public.set_updated_at();

-- Pilot records. These are real database records, not hard-coded balances in the apps.
insert into public.agents (id, full_name, phone_number)
values ('44444444-4444-4444-4444-444444444444', 'MoPay Agent 01', '+237000000002')
on conflict (id) do nothing;

insert into public.merchant_wallets (merchant_id, currency, balance)
values ('22222222-2222-2222-2222-222222222222'::uuid, 'XAF', 100000)
on conflict (merchant_id) do nothing;

insert into public.agent_wallets (agent_id, currency, balance)
values ('44444444-4444-4444-4444-444444444444'::uuid, 'XAF', 50000)
on conflict (agent_id) do nothing;

-- Second customer used for C2C tests.
insert into public.customers (id, full_name, phone_number)
values ('55555555-5555-5555-5555-555555555555', 'MO PAY CUSTOMER 02', '+237000000003')
on conflict (id) do nothing;
insert into public.wallets (customer_id, currency, balance)
values ('55555555-5555-5555-5555-555555555555'::uuid, 'XAF', 50000)
on conflict (customer_id) do nothing;

-- Generic wallet lock/update helpers.
create or replace function public._get_account_balance(
  p_kind public.account_kind,
  p_id uuid
) returns bigint
language plpgsql security definer set search_path=public,extensions as $$
declare v_balance bigint;
begin
  if p_kind='customer' then
    select balance into v_balance from public.wallets where customer_id=p_id for update;
  elsif p_kind='merchant' then
    select balance into v_balance from public.merchant_wallets where merchant_id=p_id for update;
  else
    select balance into v_balance from public.agent_wallets where agent_id=p_id for update;
  end if;
  if v_balance is null then raise exception 'ACCOUNT_NOT_FOUND'; end if;
  return v_balance;
end $$;

create or replace function public._set_account_balance(
  p_kind public.account_kind,
  p_id uuid,
  p_balance bigint
) returns void
language plpgsql security definer set search_path=public,extensions as $$
begin
  if p_balance < 0 then raise exception 'NEGATIVE_BALANCE'; end if;
  if p_kind='customer' then
    update public.wallets set balance=p_balance, updated_at=now() where customer_id=p_id;
  elsif p_kind='merchant' then
    update public.merchant_wallets set balance=p_balance, updated_at=now() where merchant_id=p_id;
  else
    update public.agent_wallets set balance=p_balance, updated_at=now() where agent_id=p_id;
  end if;
end $$;

create or replace function public._account_active(
  p_kind public.account_kind,
  p_id uuid
) returns boolean
language plpgsql security definer set search_path=public,extensions as $$
declare v_active boolean;
begin
  if p_kind='customer' then
    select is_active into v_active from public.wallets where customer_id=p_id;
  elsif p_kind='merchant' then
    select w.is_active and m.is_active into v_active from public.merchant_wallets w join public.merchants m on m.id=w.merchant_id where w.merchant_id=p_id;
  else
    select w.is_active and a.is_active into v_active from public.agent_wallets w join public.agents a on a.id=w.agent_id where w.agent_id=p_id;
  end if;
  return coalesce(v_active,false);
end $$;

-- Atomic internal transfer. This is the ledger used by all five requested flows.
create or replace function public.transfer_money(
  p_channel public.transfer_channel,
  p_sender_kind public.account_kind,
  p_sender_id uuid,
  p_receiver_kind public.account_kind,
  p_receiver_id uuid,
  p_amount bigint,
  p_pin text default null,
  p_card_token text default null,
  p_note text default null
) returns jsonb
language plpgsql security definer set search_path=public,extensions as $$
declare
  v_sender_before bigint;
  v_receiver_before bigint;
  v_sender_after bigint;
  v_receiver_after bigint;
  v_ref text := 'MP-' || upper(substr(replace(gen_random_uuid()::text,'-',''),1,14));
  v_card public.cards%rowtype;
begin
  if p_amount is null or p_amount <= 0 then
    return jsonb_build_object('success',false,'reason','INVALID_AMOUNT');
  end if;
  if p_sender_id = p_receiver_id and p_sender_kind = p_receiver_kind then
    return jsonb_build_object('success',false,'reason','SAME_ACCOUNT');
  end if;
  if not public._account_active(p_sender_kind,p_sender_id) then
    return jsonb_build_object('success',false,'reason','SENDER_NOT_AVAILABLE');
  end if;
  if not public._account_active(p_receiver_kind,p_receiver_id) then
    return jsonb_build_object('success',false,'reason','RECEIVER_NOT_AVAILABLE');
  end if;

  -- C2M via NFC can authenticate the customer card/PIN here.
  if p_card_token is not null then
    select * into v_card from public.cards where card_token=p_card_token for update;
    if not found then return jsonb_build_object('success',false,'reason','CARD_NOT_FOUND'); end if;
    if v_card.customer_id <> p_sender_id or v_card.status <> 'active' then return jsonb_build_object('success',false,'reason','CARD_NOT_ACTIVE'); end if;
    if p_pin is not null and not crypt(p_pin,v_card.pin_hash)=v_card.pin_hash then
      update public.cards set failed_pin_attempts=failed_pin_attempts+1,updated_at=now() where id=v_card.id;
      return jsonb_build_object('success',false,'reason','INVALID_PIN');
    end if;
  end if;

  -- Deterministic lock ordering prevents concurrent transfer deadlocks for same kind.
  if p_sender_kind::text || p_sender_id::text < p_receiver_kind::text || p_receiver_id::text then
    v_sender_before := public._get_account_balance(p_sender_kind,p_sender_id);
    v_receiver_before := public._get_account_balance(p_receiver_kind,p_receiver_id);
  else
    v_receiver_before := public._get_account_balance(p_receiver_kind,p_receiver_id);
    v_sender_before := public._get_account_balance(p_sender_kind,p_sender_id);
  end if;

  if v_sender_before < p_amount then
    insert into public.wallet_transfers(reference,channel,sender_kind,sender_id,receiver_kind,receiver_id,amount,status,reason,sender_balance_before,sender_balance_after,metadata)
    values(v_ref,p_channel,p_sender_kind,p_sender_id,p_receiver_kind,p_receiver_id,p_amount,'declined','INSUFFICIENT_FUNDS',v_sender_before,v_sender_before,jsonb_build_object('note',p_note));
    return jsonb_build_object('success',false,'reason','INSUFFICIENT_FUNDS','reference',v_ref);
  end if;

  v_sender_after := v_sender_before - p_amount;
  v_receiver_after := v_receiver_before + p_amount;
  perform public._set_account_balance(p_sender_kind,p_sender_id,v_sender_after);
  perform public._set_account_balance(p_receiver_kind,p_receiver_id,v_receiver_after);

  insert into public.wallet_transfers(reference,channel,sender_kind,sender_id,receiver_kind,receiver_id,amount,currency,status,sender_balance_before,sender_balance_after,receiver_balance_before,receiver_balance_after,metadata)
  values(v_ref,p_channel,p_sender_kind,p_sender_id,p_receiver_kind,p_receiver_id,p_amount,'XAF','successful',v_sender_before,v_sender_after,v_receiver_before,v_receiver_after,jsonb_build_object('note',p_note,'card_token_used',p_card_token is not null));

  if v_card.id is not null then
    update public.cards set failed_pin_attempts=0,last_used_at=now(),updated_at=now() where id=v_card.id;
  end if;

  return jsonb_build_object('success',true,'reference',v_ref,'channel',p_channel::text,'amount',p_amount,'currency','XAF','sender_balance_after',v_sender_after,'receiver_balance_after',v_receiver_after);
exception when others then
  return jsonb_build_object('success',false,'reason',sqlerrm);
end $$;

-- Customer dashboard with all balances and transfer history.
create or replace function public.get_customer_dashboard(p_customer_id uuid default '11111111-1111-1111-1111-111111111111'::uuid)
returns jsonb language sql security definer set search_path=public,extensions as $$
select jsonb_build_object(
 'customer',jsonb_build_object('id',c.id,'full_name',c.full_name,'phone_number',c.phone_number),
 'wallet',jsonb_build_object('balance',w.balance,'currency',w.currency,'is_active',w.is_active),
 'card',jsonb_build_object('card_token',cd.card_token,'masked_number',cd.masked_number,'status',cd.status,'last_used_at',cd.last_used_at),
 'transfers',coalesce((select jsonb_agg(z.item order by z.created_at desc) from (select wt.created_at,jsonb_build_object('reference',wt.reference,'channel',wt.channel,'amount',wt.amount,'status',wt.status,'reason',wt.reason,'direction',case when wt.sender_kind='customer' and wt.sender_id=c.id then 'OUT' else 'IN' end,'created_at',wt.created_at) item from public.wallet_transfers wt where (wt.sender_kind='customer' and wt.sender_id=c.id) or (wt.receiver_kind='customer' and wt.receiver_id=c.id) order by wt.created_at desc limit 50) z),'[]'::jsonb)
)
from public.customers c join public.wallets w on w.customer_id=c.id join public.cards cd on cd.customer_id=c.id where c.id=p_customer_id limit 1;
$$;

create or replace function public.get_merchant_dashboard_v2(p_merchant_id uuid default '22222222-2222-2222-2222-222222222222'::uuid)
returns jsonb language sql security definer set search_path=public,extensions as $$
select jsonb_build_object('merchant',jsonb_build_object('id',m.id,'business_name',m.business_name,'phone_number',m.phone_number,'is_active',m.is_active),'wallet',jsonb_build_object('balance',mw.balance,'currency',mw.currency),'today',jsonb_build_object('transaction_count',count(wt.id),'successful_count',count(wt.id) filter(where wt.status='successful'),'failed_count',count(wt.id) filter(where wt.status='declined'),'total_amount',coalesce(sum(wt.amount) filter(where wt.status='successful'),0)),'transfers',coalesce(jsonb_agg(jsonb_build_object('reference',wt.reference,'channel',wt.channel,'amount',wt.amount,'status',wt.status,'created_at',wt.created_at,'sender_kind',wt.sender_kind,'receiver_kind',wt.receiver_kind) order by wt.created_at desc) filter(where wt.id is not null),'[]'::jsonb))
from public.merchants m join public.merchant_wallets mw on mw.merchant_id=m.id left join public.wallet_transfers wt on ((wt.sender_kind='merchant' and wt.sender_id=m.id) or (wt.receiver_kind='merchant' and wt.receiver_id=m.id)) and wt.created_at>=date_trunc('day',now()) where m.id=p_merchant_id group by m.id,m.business_name,m.phone_number,m.is_active,mw.balance,mw.currency;
$$;

create or replace function public.get_agent_dashboard(p_agent_id uuid default '44444444-4444-4444-4444-444444444444'::uuid)
returns jsonb language sql security definer set search_path=public,extensions as $$
select jsonb_build_object('agent',jsonb_build_object('id',a.id,'full_name',a.full_name,'phone_number',a.phone_number,'is_active',a.is_active),'wallet',jsonb_build_object('balance',w.balance,'currency',w.currency),'transfers',coalesce((select jsonb_agg(jsonb_build_object('reference',x.reference,'channel',x.channel,'amount',x.amount,'status',x.status,'direction',case when x.sender_kind='agent' and x.sender_id=a.id then 'OUT' else 'IN' end,'created_at',x.created_at) order by x.created_at desc) from public.wallet_transfers x where (x.sender_kind='agent' and x.sender_id=a.id) or (x.receiver_kind='agent' and x.receiver_id=a.id)),'[]'::jsonb)) from public.agents a join public.agent_wallets w on w.agent_id=a.id where a.id=p_agent_id limit 1;
$$;

-- NFC C2M entry point. It uses the same atomic ledger as all other flows.
create or replace function public.process_nfc_c2m(p_card_token text,p_merchant_id uuid,p_amount bigint,p_pin text)
returns jsonb language plpgsql security definer set search_path=public,extensions as $$
declare v_card public.cards%rowtype; v_result jsonb;
begin
 select * into v_card from public.cards where card_token=p_card_token;
 if not found then return jsonb_build_object('success',false,'reason','CARD_NOT_FOUND'); end if;
 v_result := public.transfer_money('C2M','customer',v_card.customer_id,'merchant',p_merchant_id,p_amount,p_pin,p_card_token,null);
 return v_result;
end $$;

-- Operator settlement placeholder: the app calls the internal ledger now; this is the seam
-- where an approved MTN/Orange API adapter can be added later.
create or replace function public.operator_integration_status()
returns jsonb language sql immutable as $$
select jsonb_build_object('mtn_api','PENDING_OPERATOR_CREDENTIALS','orange_api','PENDING_OPERATOR_CREDENTIALS','internal_ledger','READY','nfc_c2m','READY','c2c','READY','m2m','READY','c2a','READY','a2c','READY');
$$;

grant execute on function public.get_customer_dashboard(uuid) to anon,authenticated;
grant execute on function public.get_merchant_dashboard_v2(uuid) to anon,authenticated;
grant execute on function public.get_agent_dashboard(uuid) to anon,authenticated;
grant execute on function public.transfer_money(public.transfer_channel,public.account_kind,uuid,public.account_kind,uuid,bigint,text,text,text) to anon,authenticated;
grant execute on function public.process_nfc_c2m(text,uuid,bigint,text) to anon,authenticated;
grant execute on function public.operator_integration_status() to anon,authenticated;

create table if not exists public.wallet_operations (
  id uuid primary key default gen_random_uuid(),
  reference text not null unique,
  customer_id uuid not null references public.customers(id) on delete restrict,
  operation text not null check (operation in ('TOPUP','CASHOUT')),
  amount bigint not null check (amount > 0),
  currency text not null default 'XAF',
  status public.transfer_status not null default 'successful',
  balance_before bigint,
  balance_after bigint,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists idx_wallet_operations_customer on public.wallet_operations(customer_id,created_at desc);

create or replace function public.customer_top_up(p_customer_id uuid,p_amount bigint,p_note text default null)
returns jsonb language plpgsql security definer set search_path=public,extensions as $$
declare v_before bigint; v_after bigint; v_ref text := 'TP-'||upper(substr(replace(gen_random_uuid()::text,'-',''),1,14));
begin
 if p_amount is null or p_amount<=0 then return jsonb_build_object('success',false,'reason','INVALID_AMOUNT'); end if;
 select balance into v_before from public.wallets where customer_id=p_customer_id and is_active=true for update;
 if v_before is null then return jsonb_build_object('success',false,'reason','WALLET_NOT_AVAILABLE'); end if;
 v_after:=v_before+p_amount;
 update public.wallets set balance=v_after,updated_at=now() where customer_id=p_customer_id;
 insert into public.wallet_operations(reference,customer_id,operation,amount,balance_before,balance_after,metadata) values(v_ref,p_customer_id,'TOPUP',p_amount,v_before,v_after,jsonb_build_object('note',p_note));
 return jsonb_build_object('success',true,'reference',v_ref,'amount',p_amount,'balance_after',v_after);
end $$;

grant execute on function public.customer_top_up(uuid,bigint,text) to anon,authenticated;
insert into public.merchants (id,business_name,phone_number,is_active)
values ('66666666-6666-6666-6666-666666666666','MoPay Merchant 02','+237000000004',true)
on conflict (id) do nothing;
insert into public.merchant_wallets (merchant_id,currency,balance)
values ('66666666-6666-6666-6666-666666666666'::uuid,'XAF',25000)
on conflict (merchant_id) do nothing;
