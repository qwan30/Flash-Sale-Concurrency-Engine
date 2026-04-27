import { OrderDetailDashboard } from "@/components/order-detail-dashboard";

export default async function OrderDetailPage({
  params,
}: {
  params: Promise<{ orderNumber: string }>;
}) {
  const { orderNumber } = await params;

  return <OrderDetailDashboard orderNumber={decodeURIComponent(orderNumber)} />;
}
